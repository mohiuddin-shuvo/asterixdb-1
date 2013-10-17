package edu.uci.ics.asterix.metadata.feeds;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.CharBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MessageListener {

    private static final Logger LOGGER = Logger.getLogger(MessageListener.class.getName());

    private int port;
    private final LinkedBlockingQueue<String> outbox;

    private ExecutorService executorService = Executors.newFixedThreadPool(10);

    private MessageListenerServer listenerServer;

    public MessageListener(int port, LinkedBlockingQueue<String> outbox) {
        this.port = port;
        this.outbox = outbox;
    }

    public void stop() {
        listenerServer.stop();
        System.out.println("STOPPED MESSAGE RECEIVING SERVICE AT " + port);
        if (!executorService.isShutdown()) {
            executorService.shutdownNow();
        }

    }

    public void start() throws IOException {
        System.out.println("STARTING MESSAGE RECEIVING SERVICE AT " + port);
        listenerServer = new MessageListenerServer(port, outbox);
        executorService.execute(listenerServer);
    }

    private static class MessageListenerServer implements Runnable {

        private final int port;
        private final LinkedBlockingQueue<String> outbox;
        private ServerSocket server;

        public MessageListenerServer(int port, LinkedBlockingQueue<String> outbox) {
            this.port = port;
            this.outbox = outbox;
        }

        public void stop() {
            try {
                server.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            char EOL = (char) "\n".getBytes()[0];
            Socket client = null;
            try {
                server = new ServerSocket(port);
                client = server.accept();
                InputStream in = client.getInputStream();
                CharBuffer buffer = CharBuffer.allocate(5000);
                char ch;
                while (true) {
                    ch = (char) in.read();
                    if (((int) ch) == -1) {
                        break;
                    }
                    while (ch != EOL) {
                        buffer.put(ch);
                        ch = (char) in.read();
                    }
                    buffer.flip();
                    String s = new String(buffer.array());
                    synchronized (outbox) {
                        outbox.add(s + "\n");
                    }
                    buffer.position(0);
                    buffer.limit(5000);
                }

            } catch (Exception e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning("Unable to start Message listener" + server);
                }
            } finally {
                if (server != null) {
                    try {
                        server.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

        }

    }

    private static class MessageParser implements Runnable {

        private Socket client;
        private IMessageAnalyzer messageAnalyzer;
        private static final char EOL = (char) "\n".getBytes()[0];

        public MessageParser(Socket client, IMessageAnalyzer messageAnalyzer) {
            this.client = client;
            this.messageAnalyzer = messageAnalyzer;
        }

        @Override
        public void run() {
            CharBuffer buffer = CharBuffer.allocate(5000);
            char ch;
            try {
                InputStream in = client.getInputStream();
                while (true) {
                    ch = (char) in.read();
                    if (((int) ch) == -1) {
                        break;
                    }
                    while (ch != EOL) {
                        buffer.put(ch);
                        ch = (char) in.read();
                    }
                    buffer.flip();
                    String s = new String(buffer.array());
                    synchronized (messageAnalyzer) {
                        messageAnalyzer.getMessageQueue().add(s + "\n");
                    }
                    buffer.position(0);
                    buffer.limit(5000);
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
            } finally {
                try {
                    client.close();
                } catch (IOException ioe) {
                    // do nothing
                }
            }
        }
    }

    public static interface IMessageAnalyzer {

        /**
         * @return
         */
        public LinkedBlockingQueue<String> getMessageQueue();

    }

}