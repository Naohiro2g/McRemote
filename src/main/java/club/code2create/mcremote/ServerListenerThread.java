package club.code2create.mcremote;

import java.io.*;
import java.net.*;
import java.util.logging.Logger;

public class ServerListenerThread implements Runnable {
	private static final Logger logger = Logger.getLogger("McR_Server");

	public ServerSocket serverSocket;
	boolean running = true;
	private static McRemote plugin;

	public ServerListenerThread(McRemote plugin, SocketAddress bindAddress) throws IOException {
		ServerListenerThread.plugin = plugin;
		serverSocket = new ServerSocket();
		serverSocket.setReuseAddress(true);
		serverSocket.bind(bindAddress);
	}

	@Override
	public void run() {
		while (running) {
			try {
				Socket newConnection = serverSocket.accept();
				if (!running) return;
				plugin.handleConnection(new RemoteSession(plugin, newConnection));
			} catch (Exception e) {
				if (running) {
					logger.warning("Error creating new connection");
					StringWriter sw = new StringWriter();
					e.printStackTrace(new PrintWriter(sw));
					logger.warning(sw.toString());
				}
			}
		}
		try {
			serverSocket.close();
		} catch (Exception e) {
			logger.warning("Error closing server socket");
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			logger.warning(sw.toString());
		}
	}
}
