package jp.redmine.redmineclient.activity.handler;

public class WebviewActionEmptyHandler implements WebviewActionInterface {
	@Override
	public void issue(int connection, int issueid) {}
	@Override
	public boolean url(String url) {return false;	}
	@Override
	public void wiki(int connection, long projectid, String title) {}
}
