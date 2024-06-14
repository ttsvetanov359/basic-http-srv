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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.google.api.LabelDescriptor;
import com.google.api.Metric;
import com.google.api.MetricDescriptor;
import com.google.api.MonitoredResource;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.monitoring.v3.CreateMetricDescriptorRequest;
import com.google.monitoring.v3.CreateTimeSeriesRequest;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.ProjectName;
import com.google.protobuf.util.Timestamps;

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

    void setSoKeepalive(boolean alive) {
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
        try {
            this.metricServiceClient.close();
        } catch (Exception e) {
            log(e);
        }
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
                final int MAX_READ = 500; // don't read large requests
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

                    // record monitoring data
                    if (monitorEnabled) {
                        reportNewClient(so.getInetAddress().getHostAddress(), count);
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
            if (args[x].startsWith("--")) { // single arg
                params.put(args[x], null);
            } else if (args[x].startsWith("-")) {
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

    protected static boolean hasDryRunParam() {
        return params.containsKey("--dry-run");
    }

    protected static boolean hasMonitorEnabledParam() {
        return params.containsKey("--enable-monitor");
    }

    /*
     * Monitoring artifacts.
     */
    private boolean monitorEnabled = false;
    private ProjectName projectName = null;
    private MetricServiceClient metricServiceClient = null;
    private final String metricType = "custom.googleapis.com/basic-http-srv/bytesCount";

    /*
     * Initate the monitoring.
     */
    private void initMonitoring() throws IOException {
        // Set the default project ID
        final String projectId = com.google.cloud.ServiceOptions.getDefaultProjectId();
        this.projectName = ProjectName.of(projectId);
        log("Monitoring service project ID: '" + this.projectName.toString() + "'");
        // Create the service client
        this.metricServiceClient = MetricServiceClient.create();
        // if successful, enable monitoring
        this.monitorEnabled = true;
        // create the necessary metric descriptor
        MetricDescriptor descriptor = MetricDescriptor.newBuilder()
                .setType(metricType)
                .addLabels(LabelDescriptor.newBuilder()
                        .setKey("client_ip")
                        .setValueType(LabelDescriptor.ValueType.STRING))
                .addLabels(LabelDescriptor.newBuilder()
                        .setKey("cluster_name")
                        .setValueType(LabelDescriptor.ValueType.STRING))
                .addLabels(LabelDescriptor.newBuilder()
                        .setKey("container_name")
                        .setValueType(LabelDescriptor.ValueType.STRING))
                .addLabels(LabelDescriptor.newBuilder()
                        .setKey("location")
                        .setValueType(LabelDescriptor.ValueType.STRING))
                .addLabels(LabelDescriptor.newBuilder()
                        .setKey("namespace_name")
                        .setValueType(LabelDescriptor.ValueType.STRING))
                .addLabels(LabelDescriptor.newBuilder()
                        .setKey("pod_name")
                        .setValueType(LabelDescriptor.ValueType.STRING))
                .setDescription("HttpServer client connection bytes count")
                .setMetricKind(MetricDescriptor.MetricKind.GAUGE)
                .setValueType(MetricDescriptor.ValueType.INT64)
                .build();
        CreateMetricDescriptorRequest request = CreateMetricDescriptorRequest.newBuilder()
                .setName(this.projectName.toString())
                .setMetricDescriptor(descriptor)
                .build();
        descriptor = this.metricServiceClient.createMetricDescriptor(request);
        log("Created descriptor " + descriptor.getName());
    }

    /*
     * Configured labels from the environment variables
     */
    protected final static String GKE_MONITORING_CLUSTER_NAME = System.getenv("GKE_MONITORING_CLUSTER_NAME");
    protected final static String GKE_MONITORING_CONTAINER_NAME = System.getenv("GKE_MONITORING_CONTAINER_NAME");
    protected final static String GKE_MONITORING_LOCATION = System.getenv("GKE_MONITORING_LOCATION");
    protected final static String GKE_MONITORING_NAMESPACE_NAME = System.getenv("GKE_MONITORING_NAMESPACE_NAME");
    protected final static String GKE_MONITORING_POD_NAME = System.getenv("GKE_MONITORING_POD_NAME");

    /*
     * 
     */
    private void reportNewClient(String clientIP, long bytesCount) {
        // Prepares an individual data point
        TimeInterval interval = TimeInterval.newBuilder()
                .setEndTime(Timestamps.fromMillis(System.currentTimeMillis()))
                .build();
        TypedValue value = TypedValue.newBuilder().setInt64Value(bytesCount).build();
        Point point = Point.newBuilder().setInterval(interval).setValue(value).build();

        List<Point> pointList = new ArrayList<>();
        pointList.add(point);

        // Prepares the metric descriptor
        Map<String, String> metricLabels = new HashMap<>(1);
        metricLabels.put("client_ip", clientIP);
        Metric metric = Metric.newBuilder()
                .setType(metricType)
                .putAllLabels(metricLabels)
                .build();

        // Prepares the monitored resource descriptor
        Map<String, String> resourceLabels = new HashMap<>();
        resourceLabels.put("project_id", this.projectName.getProject());
        resourceLabels.put("cluster_name", GKE_MONITORING_CLUSTER_NAME);
        resourceLabels.put("container_name", GKE_MONITORING_CONTAINER_NAME);
        resourceLabels.put("location", GKE_MONITORING_LOCATION);
        resourceLabels.put("namespace_name", GKE_MONITORING_NAMESPACE_NAME);
        resourceLabels.put("pod_name", GKE_MONITORING_POD_NAME);

        MonitoredResource resource = MonitoredResource.newBuilder()
                .setType("k8s_container") // is there a constant for the type in
                                          // com.google.api.MonitoredResourceDescriptor?
                .putAllLabels(resourceLabels)
                .build();

        // Prepares the time series request
        TimeSeries timeSeries = TimeSeries.newBuilder()
                .setMetric(metric)
                .setResource(resource)
                .addAllPoints(pointList)
                .build();
        List<TimeSeries> timeSeriesList = new ArrayList<>(1);
        timeSeriesList.add(timeSeries);

        CreateTimeSeriesRequest request = CreateTimeSeriesRequest.newBuilder()
                .setName(projectName.toString())
                .addAllTimeSeries(timeSeriesList)
                .build();

        // Writes time series data
        metricServiceClient.createTimeSeries(request);

        log("Done writing time series data");
    }

    /*
     * Print exception stack
     */
    private static StringBuffer printStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.getBuffer();
    }

    /*
     * Main method.
     */
    public static void main(String[] args) {
        parseParams(args);
        log(params);
        if (hasDryRunParam()) {
            return;
        }

        log("System data dump");
        log(response);

        HttpServer srv = new HttpServer(params.get("-p"), params.get("-b"));
        if (params.containsKey("-soTime")) {
            srv.setSoTimeout(params.get("-soTime"));
        }
        if (params.containsKey("-soAlive")) {
            srv.setSoKeepalive(params.get("-soAlive") != 0);
        }

        // monitoring
        if (hasMonitorEnabledParam()) {
            try {
                srv.initMonitoring();
            } catch (Exception e) {
                StringBuffer sb = new StringBuffer();
                sb.append("Error initiating the Cloud Monitoring" + RN);
                sb.append(printStackTrace(e));
                System.err.print(sb);
            }
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
