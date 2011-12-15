package com.shsz.young.cftp;

import java.io.IOException;
import java.net.SocketException;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CFtp {

	private Logger log = LoggerFactory.getLogger(CFtp.class);

	private FTPClient client = new FTPClient();
	private String host;
	private int port;
	private String username;

	private boolean connected = false;
	private boolean loggedIn = false;

	public boolean isConnected() {
		return connected;
	}

	public boolean isLoggedIn() {
		return loggedIn;
	}
	
	public FTPClient getClient() {
		return client;
	}

	public CFtp() {

	}

	public CFtp(String host) {
		this(host, 21);
	}

	public CFtp(String host, int port) {
		this.host = host;
		this.port = port;
	}

	public void connect() throws SocketException, IOException {
		client.connect(host, port);
		if (FTPReply.isPositiveCompletion(client.getReplyCode())) {
			this.connected = true;
			log.info("ftp连接成功");
		} else {
			log.info("ftp连接失败");
		}
	}

	public void disconnect() throws IOException {
		if (loggedIn)
			client.logout();
		client.disconnect();
		log.info("ftp断开连接");
		this.connected = false;
	}

	public void login(String username, String password) throws IOException {
		if (!connected) {
			log.error("ftp未在连接状态");
			return;
		}
		if (loggedIn) {
			log.error("重复登录，忽略");
			return;
		}
		if (client.login(username, password)) {
			this.username = username;
			this.loggedIn = true;
			log.info("ftp登录成功");
		} else {
			log.info("ftp登录失败: " + client.getReplyString());
		}
	}

	public boolean logout() throws IOException {
		if (!connected)
			return false;
		if (!loggedIn)
			return false;
		if (client.logout()) {
			log.info("ftp登出成功");
			loggedIn = false;
			return true;
		} else {
			log.error("ftp登出时出现错误");
		}
		return false;
	}
	
	public boolean activeTest() {
		if (!(connected && loggedIn)) return false;
		try {
			log.debug("活动测试");
			return client.sendNoOp();
		} catch (IOException e) {
			log.info("网络异常，活动测试不成功");
		}
		return false;
	}
}
