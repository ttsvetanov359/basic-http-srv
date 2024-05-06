package com.goo.test.k8s;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class HttpServer implements Runnable {

    private final static int DFLT_PORT = 12345;
    private final static int DFLT_CLIENTS = 20;
    private final static int DFLT_RUNTIME = 0;
    private int port = DFLT_PORT;
    private int clients = DFLT_CLIENTS;
    private int soTimeout = 2000;
    private boolean soKeepalive = false;
    private ServerSocket ss = null;

    protected static boolean gcpLogging = System.getProperty("gcplogging") != null;

    static final StringBuffer response = new StringBuffer(1000);
    static final String RN = "\r\n";
    static {
        try {
            response.append("Host: ");
            response.append(InetAddress.getLocalHost());
        } catch (UnknownHostException e) {
            System.err.println("Can't resolve local host: " + e);
        }
        response.append(RN);
        response.append("Environment: ");
        response.append(System.getenv());
        response.append(RN);
        response.append("System properties: ");
        response.append(System.getProperties().toString().replace("\r", "\\r").replace("\n", "\\n"));
        response.append(RN);
    }

    HttpServer() {
        this(DFLT_PORT, DFLT_CLIENTS);
    }

    HttpServer(int port, int clients) {
        this.port = port;
        this.clients = clients;
    }

    void setSoTimeout(int timeout) {
        if (timeout < 0)
            return;
        this.soTimeout = timeout;
    }

    void setSoKeepalive (boolean alive) {
        this.soKeepalive = alive;
    }

    /*
     * Terminate the server.
     */
    private boolean running = true;

    void shutdown() throws InterruptedException {
        try {
            ss.close();
        } catch (IOException e) {
            log(e);
            throw new InterruptedException("server shutdown");
        }
        running = false;
    }

    public void run() {
        try {
            ss = new ServerSocket(port, clients, InetAddress.getByAddress(new byte[] { 0, 0, 0, 0 }));
            log("server listens to tcp:" + ss.getInetAddress() + ":" + ss.getLocalPort() + ", backlog " + clients);
        } catch (Exception e) {
            log("Cannot open socket " + this);
            log(e);
            return;
        }

        try {
            while (running) {
                try {
                    HttpClient clnt = new HttpClient(ss.accept());
                    clnt.start();
                    log("Client connected: " + clnt);
                } catch (IOException e) {
                    log("Error with the client connection: " + e);
                    log(e);
                }
            }
        } finally {
            if (ss != null)
                try {
                    ss.close();
                } catch (Exception exc) {
                    log(exc);
                }
        }

    }

    @Override
    public String toString() {
        return HttpServer.class.getName() + " {port:" + this.port + ",backlog:" + this.clients + "}";
    }

    class HttpClient extends Thread {
        private Socket so;

        HttpClient(Socket so) {
            this.so = so;
            super.setName("client " + so.getInetAddress().getHostAddress() + ":" + so.getPort() + "--"
                    + so.getLocalAddress().getHostAddress() + ":" + so.getLocalPort());
            try {
                log("client socket properties:" + " timeout:" + so.getSoTimeout()
                        + "; keepalive:" + so.getKeepAlive() + "; linger:" + so.getSoLinger()
                        + "; tcpNoDelay:" + so.getTcpNoDelay());
            } catch (SocketException e) {
                log("cannot log the socket details");
                log(e);
            }
        }

        /*
         * 200 OK
         */
        private void replyWithOK() {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(so.getOutputStream()))) {
                writer.write("HTTP/1.0 200 OK" + RN);
                writer.write("Connection: close" + RN);
                writer.write("Content-Type: text/plain" + RN);
                writer.write("Content-Length: " + response.length() + RN + RN);
                writer.write(response.toString());
            } catch (IOException e) {
                log("Unble to write the response.");
                log(e);
            }
        }

        /*
         * 400 Bad Request
         */
        private void replyWithBadRequest() {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(so.getOutputStream()))) {
                writer.write("HTTP/1.0 400 Bad Request" + RN);
                writer.write("Connection: close" + RN + RN);
                writer.write(response.toString());
            } catch (IOException e) {
                log("Unble to write the response.");
                log(e);
            }
        }

        @Override
        public void run() {
            try {
                so.setSoTimeout(soTimeout);
                so.setKeepAlive(soKeepalive);
                BufferedReader reader = new BufferedReader(new InputStreamReader(so.getInputStream()));
                final int MAX_READ = 500;
                char[] buff = new char[MAX_READ];
                StringBuffer request = new StringBuffer(MAX_READ * 2);

                try {
                    int count = 0;
                    while (reader.ready()) {
                        int read = reader.read(buff);
                        request.append(buff, 0, read);
                        count += read;
                        if (count >= MAX_READ)
                            break;
                    }
                } catch (SocketTimeoutException timeout) {
                    log("Timeout error: " + timeout);
                }
                log("client sent: {" + request + "}");

                // reply
                if (request.toString().startsWith("GET ")) {
                    replyWithOK();

                } else {
                    replyWithBadRequest();
                }
            } catch (Exception e) {
                log(e);
            } finally {
                try {
                    so.close();
                } catch (Exception exc) {
                    log(exc);
                }

            }
        }

        @Override
        public String toString() {
            return this.getName();
        }

    }

    /*
     * My logger
     */
    static void log(Object message) {
        final String prefix = "[" + new java.util.Date() + "] ";
        if (message instanceof Throwable) {
            Throwable t = (Throwable) message;
            StringWriter w = new StringWriter(1000);
            t.printStackTrace(new PrintWriter(w));
            message = w.toString();
        }
        final String logMessage = prefix + message;
        System.out.println(logMessage);
        if (gcpLogging) {
            Logger.getLogger(HttpServer.class.getName()).info(logMessage);
        }
    }

    private static Map<String, Integer> params = new HashMap<String, Integer>();
    private static Map<String, Integer> PARAM_DEFAULTS = new HashMap<String, Integer>();
    static {
        PARAM_DEFAULTS.put("-p", DFLT_PORT);
        PARAM_DEFAULTS.put("-b", DFLT_CLIENTS);
        PARAM_DEFAULTS.put("-t", DFLT_RUNTIME);
    }

    protected static void parseParams(String[] args) {
        int x = 0;
        while (args.length > x) {
            log("arg[" + x + "]=" + args[x]);
            if (args[x].startsWith("-")) {
                if (x + 1 < args.length) {
                    try {
                        params.put(args[x], Integer.parseInt(args[x + 1]));
                    } catch (NumberFormatException e) {
                        params.put(args[x], PARAM_DEFAULTS.get(args[x]));
                        log("Bad parameter value: " + args[x] + "=" + args[x + 1]);
                        x++;
                    }
                } else {
                    params.put(args[x], 0);
                }
                x++;
                continue;
            }
            x++;
        }
    }

    protected static boolean isDryRun() {
        return params.containsKey("-dry-run");
    }

    public static void main(String[] args) {
        parseParams(args);
        log(params);
        if (isDryRun()) {
            return;
        }

        log("System data dump");
        log(response);

        Runnable srv = new HttpServer(params.get("-p"), params.get("-b"));
        if (params.containsKey("-soTime")) {
            ((HttpServer)srv).setSoTimeout(params.get("-soTime"));
        }
        if (params.containsKey("-soAlive")) {
            ((HttpServer)srv).setSoKeepalive(params.get("-soAlive") != 0);
        }

        Thread t = new Thread(srv);
        t.start();
        log(srv + " started.");
        try {
            t.join(params.get("-t"));
            ((HttpServer) srv).shutdown();
        } catch (InterruptedException e) {
            log(e);
        }
        log(srv + " stopped.");
    }
}
