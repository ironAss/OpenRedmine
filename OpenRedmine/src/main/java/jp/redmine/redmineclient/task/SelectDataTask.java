package jp.redmine.redmineclient.task;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import jp.redmine.redmineclient.BuildConfig;
import jp.redmine.redmineclient.entity.RedmineConnection;
import jp.redmine.redmineclient.parser.BaseParser;
import jp.redmine.redmineclient.url.RemoteUrl;
import jp.redmine.redmineclient.url.RemoteUrl.requests;
import jp.redmine.redmineclient.url.RemoteUrl.versions;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.net.Uri;
import android.net.Uri.Builder;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;
import android.widget.ArrayAdapter;

public abstract class SelectDataTask<T,P> extends AsyncTask<P, Integer, T> {
	public final String CHARSET = "UTF-8";
	/**
	 * Notify error request on UI thread
	 * @param statuscode http response code
	 */
	abstract protected void onErrorRequest(int statuscode);
	/**
	 * Notify progress on UI thread
	 * @param max total count of the items
	 * @param proc current count of the items
	 */
	abstract protected void onProgress(int max,int proc);

	/**
	 * Store the last exception (reference by UI thread)
	 */
	private volatile Exception lasterror;

	interface ProgressKind{
		public int progress = 1;
		public int error = 2;
		public int unknown = 3;
	}

	@Override
	protected final void onProgressUpdate(Integer... values) {
		super.onProgressUpdate(values);
		switch(values[0]){
		case ProgressKind.progress:
			onProgress(values[1],values[2]);
			break;
		case ProgressKind.error:
			onErrorRequest(values[1]);
			break;
		case ProgressKind.unknown:
			onError(lasterror);
			break;
		default:
		}
	}
	protected void onError(Exception lasterror){
		Log.e("SelectDataTask", "background", lasterror);
	}

	protected void notifyProgress(int max,int proc){
		super.publishProgress(ProgressKind.progress,max,proc);
	}

	protected void publishErrorRequest(int status){
		super.publishProgress(ProgressKind.error,status);
	}
	protected void publishError(Exception e){
		lasterror = e;
		super.publishProgress(ProgressKind.unknown);
	}

	protected void helperAddItems(ArrayAdapter<T> listAdapter,List<T> items){
		if(items == null)
			return;
		listAdapter.notifyDataSetInvalidated();
		for (T i : items){
			listAdapter.add(i);
		}
		listAdapter.notifyDataSetChanged();
	}

	protected void helperSetupParserStream(InputStream stream,BaseParser<?,?> parser) throws XmlPullParserException{
		XmlPullParser xmlPullParser = Xml.newPullParser();
		xmlPullParser.setInput(stream, CHARSET);
		parser.setXml(xmlPullParser);
	}

	private boolean isGZipHttpResponse(HttpResponse response) {
		Header header = response.getEntity().getContentEncoding();
		if (header == null) return false;
		String value = header.getValue();
		return (!TextUtils.isEmpty(value) && value.contains("gzip"));
	}
	private boolean isDeflateHttpResponse(HttpResponse response) {
		Header header = response.getEntity().getContentEncoding();
		if (header == null) return false;
		String value = header.getValue();
		return (!TextUtils.isEmpty(value) && value.contains("deflate"));
	}
	protected boolean fetchData(SelectDataTaskConnectionHandler connectionhandler, RedmineConnection connection,RemoteUrl url,SelectDataTaskDataHandler handler){
		return fetchData(RemoteType.get,connectionhandler,connection,url,handler,null);
	}
	protected boolean putData(SelectDataTaskConnectionHandler connectionhandler,RedmineConnection connection,RemoteUrl url,SelectDataTaskDataHandler handler, SelectDataTaskPutHandler puthandler){
		return fetchData(RemoteType.put,connectionhandler,connection,url,handler,puthandler);
	}
	protected boolean postData(SelectDataTaskConnectionHandler connectionhandler,RedmineConnection connection,RemoteUrl url,SelectDataTaskDataHandler handler, SelectDataTaskPutHandler puthandler){
		return fetchData(RemoteType.post,connectionhandler,connection,url,handler,puthandler);
	}

	protected enum RemoteType{
		get,
		put,
		post,
		delete,
	}
	protected boolean fetchData(RemoteType type, SelectDataTaskConnectionHandler connectionhandler,RedmineConnection connection,RemoteUrl url,SelectDataTaskDataHandler handler, SelectDataTaskPutHandler puthandler){
		url.setupRequest(requests.xml);
		url.setupVersion(versions.v130);
		return fetchData(type,connectionhandler, url.getUrl(connection.getUrl()),handler,puthandler);
	}
	protected boolean fetchData(RemoteType type, SelectDataTaskConnectionHandler connectionhandler,Builder builder
			,final SelectDataTaskDataHandler handler,SelectDataTaskPutHandler puthandler){
		Uri remoteurl = builder.build();
		DefaultHttpClient client = connectionhandler.getHttpClient();
		Boolean isOk = false;
		try {
			URI uri = new URI(remoteurl.toString());
			HttpUriRequest msg = null;
			switch(type){
			case get:
				HttpGet get = new HttpGet(uri);
				msg = get;
				break;
			case delete:
				break;
			case post:
				HttpPost post = new HttpPost(new URI(remoteurl.toString()));
				post.setEntity(puthandler.getContent());
				msg = post;
				break;
			case put:
				HttpPut put = new HttpPut(new URI(remoteurl.toString()));
				put.setEntity(puthandler.getContent());
				msg = put;
				break;
			default:
				return false;

			}
			connectionhandler.setupOnMessage(msg);
			msg.setHeader("Accept-Encoding", "gzip, deflate");
			if(BuildConfig.DEBUG){
				Log.i("request", "Url: " + msg.getURI().toASCIIString());
				for(Header h : msg.getAllHeaders())
					Log.d("request", "Header:" + h.toString());
				if(type == RemoteType.get && false){
					client.execute(msg, new ResponseHandler<Boolean>() {

						@Override
						public Boolean handleResponse(HttpResponse response)
								throws ClientProtocolException, IOException {
							int status = response.getStatusLine().getStatusCode();
							InputStream stream = response.getEntity().getContent();
							if (isGZipHttpResponse(response)) {
								stream =  new GZIPInputStream(stream);
							} else if(isDeflateHttpResponse(response)){
								stream =  new InflaterInputStream(stream);
							}
							Log.d("requestDebug", "Status: " + String.valueOf(status));
							BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
						    String str;
					    	Log.d("requestDebug", ">>Dump start>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
						    while((str = reader.readLine()) != null){
						    	Log.d("requestDebug", str);
						    }
					    	Log.d("requestDebug", "<<Dump end<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
							return false;
						}
						
					});
				}
			}
			// fetch remote
			isOk = client.execute(msg, new ResponseHandler<Boolean>() {

				@Override
				public Boolean handleResponse(HttpResponse response)
						throws ClientProtocolException, IOException {
					int status = response.getStatusLine().getStatusCode();
					long length = response.getEntity().getContentLength();
					if(BuildConfig.DEBUG){
						Log.i("request", "Status: " + status);
						Log.i("request", "Protocol: " + response.getProtocolVersion());
						Log.i("request", "Length: " + length);
					}
					InputStream stream = response.getEntity().getContent();
					if (isGZipHttpResponse(response)) {
						if(BuildConfig.DEBUG) Log.i("request", "Gzip: Enabled");
						stream =  new GZIPInputStream(stream);
					} else if(isDeflateHttpResponse(response)){
						if(BuildConfig.DEBUG) Log.i("request", "Deflate: Enabled");
						stream =  new InflaterInputStream(stream);
					}
					switch(status){
					case HttpStatus.SC_OK:
					case HttpStatus.SC_CREATED:
						try {
							if(length != 0)
								handler.onContent(stream);
							return true;
						} catch (XmlPullParserException e) {
							publishError(e);
						} catch (SQLException e) {
							publishError(e);
						}
						break;
					default:
						publishErrorRequest(status);
						if(BuildConfig.DEBUG){
							Log.d("requestError", "Status: " + String.valueOf(status));
							BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
						    String str;
						    while((str = reader.readLine()) != null){
						    	Log.d("requestError", str);
						    }
						}
						break;
					}
					return false;
				}

			});
		} catch (URISyntaxException e) {
			publishErrorRequest(404);
		} catch (ClientProtocolException e) {
			publishError(e);
		} catch (IOException e) {
			publishError(e);
		} catch (SQLException e) {
			publishError(e);
		} catch (IllegalArgumentException e) {
			publishError(e);
		} catch (ParserConfigurationException e) {
			publishError(e);
		} catch (TransformerException e) {
			publishError(e);
		}
		if(!isOk)
			connectionhandler.close();
		return (isOk == true);
	}
}
