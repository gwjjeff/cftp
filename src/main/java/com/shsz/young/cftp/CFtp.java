package com.shsz.young.cftp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.util.Arrays;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CFtp {

	public static final String FU_BEGIN = "begin";
	public static final String FU_SUCCESS = "success";
	public static final String FU_FAILED = "failed";

	private static Logger log = LoggerFactory.getLogger(CFtp.class);
	
	public static int Count = 0;

	private FTPClient client = new FTPClient();
	private String host;
	private int port;
	private String username;
	private String serverEncoding;

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
		Count++;
	}

	public CFtp(String host) {
		this(host, 21, "UTF-8");
	}

	public CFtp(String host, int port, String serverEncoding) {
		Count++;
		this.host = host;
		this.port = port;
		this.serverEncoding = serverEncoding;
	}

	public void open() {
		try {
			client.connect(host, port);
		} catch (SocketException e) {
			log.error("连接ftp失败，网络异常");
		} catch (IOException e) {
			log.error("连接ftp失败，IO异常");
		}
		if (FTPReply.isPositiveCompletion(client.getReplyCode())) {
			this.connected = true;
			log.info("ftp连接成功");
		} else {
			try {
				client.disconnect();
			} catch (IOException e) {
				log.error("ftp连接断开时IO异常");
			}
			log.info("ftp连接失败");
		}
	}

	public void quit() {
		if (loggedIn)
			try {
				logout();
				client.disconnect();
			} catch (IOException e) {
				log.error("ftp连接断开时IO异常");
			}
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
			log.info("ftp登录成功: " + username);
			enterClientMode();
			if (log.isDebugEnabled()) {
				log.debug("SystemType\t" + client.getSystemType());
				log.debug("AutodetectUTF8\t" + client.getAutodetectUTF8());
				log.debug("ControlEncoding\t" + client.getControlEncoding());
				log.debug("PassiveHost\t" + client.getPassiveHost());
				log.debug("WorkingDir\t" + client.printWorkingDirectory());
				log.debug("BufferSize\t" + client.getBufferSize());
				log.debug("SoTimeout(ms)\t" + client.getSoTimeout());
				log.debug("ConnectTimeout(ms)\t" + client.getConnectTimeout());
				log.debug("ControlKeepAliveReplyTimeout(ms)\t" + client.getControlKeepAliveReplyTimeout());
				log.debug("ControlKeepAlive(s)\t" + client.getControlKeepAliveTimeout());
				log.debug("DataConnectionMode\t" + client.getDataConnectionMode());
				log.debug("KeepAlive\t" + client.getKeepAlive());
				log.debug("ListHiddenFiles\t" + client.getListHiddenFiles());
				log.debug("Names" + Arrays.toString(client.listNames()));
				log.debug(client.listHelp());
				log.debug("Status\t" + client.getStatus());
			}
		} else {
			log.info("ftp登录失败: " + client.getReplyString());
		}
	}

	protected void enterClientMode() {
		client.setControlEncoding(serverEncoding);
		client.enterLocalPassiveMode();
		client.setControlKeepAliveTimeout(15);
		try {
			client.setFileType(FTP.BINARY_FILE_TYPE);
			log.info("默认client mode设置成功");
		} catch (IOException e) {
			log.info("BINARY_FILE_TYPE设置失败");
			e.printStackTrace();
		}
	}

	private boolean logout() throws IOException {
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
		if (!(connected && loggedIn))
			return false;
		try {
			log.debug("活动测试");
			return client.sendNoOp();
		} catch (IOException e) {
			log.info("网络异常，活动测试不成功");
		}
		return false;
	}

	public boolean cd(String pathname) {
		if (!(connected && loggedIn))
			return false;
		try {
			return client.changeWorkingDirectory(pathname);
		} catch (IOException e) {
			log.debug("网络异常，cwd操作不成功");
		}
		return false;
	}

	public boolean upload(String local, String remote) throws IOException {
		boolean done = false;
		InputStream input = null;
		try {
			fileUploadEvent(FU_BEGIN, local, remote);
			input = new FileInputStream(local);
			done = client.storeFile(remote, input);
			input.close();
			if (done)
				fileUploadEvent(FU_SUCCESS, local, remote);
		} catch (FileNotFoundException e) {
			log.info("错误的文件名: local: " + local);
		} finally {
			try {
				if (input != null)
					input.close();
			} catch (IOException e2) {
			}
		}
		if (!done)
			fileUploadEvent(FU_FAILED, local, remote);
		return done;
	}

	public boolean upload(String file) throws IOException {
		return upload(file, new File(file).getName());
	}

	public boolean download(String remote, String local) throws IOException {
		boolean done = false;
		OutputStream output = null;
		try {
			output = new FileOutputStream(local);
			done = client.retrieveFile(remote, output);
			output.close();
			if (done)
				fileEvent("成功下载文件: remote: " + remote + " local: " + local);
		} catch (FileNotFoundException e) {
			fileEvent("创建文件失败: local: " + local);
		} finally {
			try {
				if (output != null)
					output.close();
			} catch (IOException e2) {
			}
		}
		if (!done)
			fileEvent("下载文件失败: remote: " + remote);
		return done;
	}

	public boolean download(String file) throws IOException {
		return download(file, file);
	}

	public boolean delete(String remote) throws IOException {
		boolean done = false;
		done = client.deleteFile(remote);
		if (done) {
			fileEvent("成功删除远程文件" + remote);
		} else {
			fileEvent("删除远程文件失败" + remote);
		}
		return done;
	}

	protected void fileUploadEvent(String status, String local, String remote) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("上传文件状态  %s, local: %s, remote: %s", status, local, remote));
		}
	}

	protected void fileEvent(String logs) {
		if (log.isDebugEnabled()) {
			log.debug(logs);
		}
	}
}
