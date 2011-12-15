package com.shsz.young.cftp;

import java.io.IOException;
import java.net.SocketException;

import org.apache.commons.net.ftp.FTPClient;

public class CFtp {
	private FTPClient client = new FTPClient();
	private String host;
	private int port;
	private String username;
	private String password;
	public CFtp() {
		
	}
	public CFtp(String host, int port) {
		this.host = host;
		this.port = port;
	}
	public void connect() throws SocketException, IOException {
		client.connect(host, port);
	}
	public void disconnect() throws IOException {
		client.disconnect();
	}
}
