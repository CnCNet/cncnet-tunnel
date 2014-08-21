/*
 * Copyright (c) 2013 Toni Spets <toni.spets@iki.fi>
 * 
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package org.cncnet.tunnel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 *
 * @author Toni Spets <toni.spets@iki.fi>
 */
public class TunnelController implements HttpHandler, Runnable {

    private class Lock {
        public long firstRequest;
        public int games;

        public Lock(long firstRequest) {
            this.firstRequest = firstRequest;
        }

        public void poke() {
            this.games++;
        }
    }

    private Map<Short, Client> clients;

    private String name;
    private String password;
    private int maxclients;
    private int port;
    private String master;
    private String masterpw = null;
    private int iplimit;
    private Queue<Short> pool;
    private volatile boolean maintenance = false;
    final private ConcurrentHashMap<String, Lock> locks;

    public TunnelController(String name, String password, int port, int maxclients, String master, String masterpw, int iplimit) {
        clients = new ConcurrentHashMap<Short, Client>();

        this.name = name;
        this.password = password;
        this.maxclients = maxclients;
        this.port = port;
        this.master = master;
        this.masterpw = masterpw;
        this.iplimit = iplimit;
        this.pool = new ConcurrentLinkedQueue<Short>();
        this.locks = new ConcurrentHashMap<String, Lock>();

        long start = System.currentTimeMillis();
        ArrayList<Short> allShort = new ArrayList<Short>();
        for (short i = Short.MIN_VALUE; i < Short.MAX_VALUE; i++) {
            allShort.add(i);
        }
        Collections.shuffle(allShort);
        pool.addAll(allShort);

        Main.log("TunnelController: Took " + (System.currentTimeMillis() - start) + "ms to initialize pool.");
    }

    public Client getClient(Short clientId) {
        return clients.get(clientId);
    }

    private void handleRequest(HttpExchange t) throws IOException {
        String params = t.getRequestURI().getQuery();
        List<InetAddress> addresses = new ArrayList<InetAddress>();
        String requestAddress = t.getRemoteAddress().getAddress().getHostAddress();
        int requestedAmount = 0;
        boolean pwOk = (password == null);

        if (params == null)
            params = "";

        String[] pairs = params.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String kv[] = pairs[i].split("=");
            if (kv.length != 2)
                continue;

            kv[0] = URLDecoder.decode(kv[0], "UTF-8");
            kv[1] = URLDecoder.decode(kv[1], "UTF-8");

            if (kv[0].equals("clients")) {
                requestedAmount = Integer.parseInt(kv[1]);
            }

            if (kv[0].equals("password") && !pwOk && kv[1].equals(password)) {
                pwOk = true;
            }
        }

        if (!pwOk) {
            // Unauthorized
            Main.log("Request was unauthorized.");
            t.sendResponseHeaders(401, 0);
            t.getResponseBody().close();
            return;
        }

        if (requestedAmount < 2 || requestedAmount > 8) {
            // Bad Request
            Main.log("Request had invalid requested amount (" + requestedAmount + ").");
            t.sendResponseHeaders(400, 0);
            t.getResponseBody().close();
            return;
        }

        if (maintenance) {
            // Service Unavailable
            Main.log("Request to start a new game was denied because of maintenance.");
            t.sendResponseHeaders(503, 0);
            t.getResponseBody().close();
            return;
        }

        Lock curLock = locks.get(requestAddress);
        // lock the request ip out until this router is collected
        if (iplimit > 0 && curLock != null && curLock.games >= iplimit) {
            // Too Many Requests
            Main.log("Same address tried to request more than " + iplimit + " routers.");
            t.sendResponseHeaders(429, 0);
            t.getResponseBody().close();
            return;
        }

        StringBuilder ret = new StringBuilder();

        synchronized (clients) {
            if (requestedAmount + clients.size() > maxclients) {
                // Service Unavailable
                Main.log("Request wanted more than we could provide.");
                t.sendResponseHeaders(503, 0);
                t.getResponseBody().close();
                return;
            }

            ret.append("[");

            // for thread safety, we just try to reserve slots (actually we are
            // double synchronized right now, makes little sense)
            ArrayList<Short> reserved = new ArrayList<Short>();
            for (int i = 0; i < requestedAmount; i++) {
                Short clientId = pool.poll();
                if (clientId != null) {
                    reserved.add(clientId);
                }
            }

            if (reserved.size() == requestedAmount) {
                boolean frist = true;
                for (Short clientId : reserved) {
                    clients.put(clientId, new Client(clientId, reserved));
                    Main.log("Client " + clientId + " allocated.");
                    if (frist) {
                        frist = false;
                    } else {
                        ret.append(",");
                    }
                    ret.append(clientId);
                }
            } else {
                // return our reservations if any
                for (Short clientId : reserved) {
                    pool.add(clientId);
                }
                // Service Unavailable
                Main.log("Request wanted more than we could provide and we also exhausted our queue.");
                t.sendResponseHeaders(503, 0);
                t.getResponseBody().close();
                return;
            }
            ret.append("]");
        }

        if (iplimit > 0) {
            synchronized (locks) {
                long now = System.currentTimeMillis();
                Lock l = locks.get(requestAddress);
                if (l == null) {
                    l = new Lock(now);
                }

                l.poke();
                locks.put(requestAddress, l);
            }
        }

        t.sendResponseHeaders(200, ret.length());
        OutputStream os = t.getResponseBody();
        os.write(ret.toString().getBytes());
        os.close();
    }

    private void handleStatus(HttpExchange t) throws IOException {
        String response = (maxclients - clients.size()) + " slots free.\n" + clients.size() + " slots in use.\n";
        Main.log("Response: " + response);
        t.sendResponseHeaders(200, response.length());
        OutputStream os = t.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    private void handleMaintenance(HttpExchange t) throws IOException {
        setMaintenance();
        t.sendResponseHeaders(200, 0);
        t.getResponseBody().close();
    }

    public void setMaintenance() {
        maintenance = true;
        Main.log("Maintenance mode enabled, no new games can be started.\n");

        if (master != null) {
            try {
                URL url = new URL(
                    master + "?version=2"
                    + "&name=" + URLEncoder.encode(name, "US-ASCII")
                    + "&password=" + (password == null ? "0" : "1")
                    + "&port=" + port
                    + "&clients=" + clients.size()
                    + "&maxclients=" + maxclients
                    + (masterpw != null ? "&masterpw=" + URLEncoder.encode(masterpw, "US-ASCII") : "")
                    + "&maintenance=1"
                );
                HttpURLConnection con = (HttpURLConnection)url.openConnection();
                con.setRequestMethod("GET");
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                con.connect();
                con.getInputStream().close();
                con.disconnect();
                Main.log("Master notified of maintenance.\n");
            } catch (FileNotFoundException e) {
                Main.log("Master server reported error 404.");
            } catch (MalformedURLException e) {
                Main.log("Failed to send heartbeat: " + e.toString());
            } catch (IOException e) {
                Main.log("Failed to send heartbeat: " + e.toString());
            }
        }
    }

    @Override
    public void handle(HttpExchange t) throws IOException {
        String uri = t.getRequestURI().toString();
        t.getRequestBody().close();

        Main.log("HTTPRequest: " + uri);

        try {
            if (uri.startsWith("/request")) {
                handleRequest(t);
            } else if (uri.startsWith("/status")) {
                handleStatus(t);
            } else if (uri.startsWith("/maintenance/")) {
                handleMaintenance(t);
            } else {
                t.sendResponseHeaders(400, 0);
            }
        } catch (IOException e) {
            Main.log("Error: " + e.getMessage());
            String error = e.getMessage();
            t.sendResponseHeaders(500, error.length());
            OutputStream os = t.getResponseBody();
            os.write(error.getBytes());
            os.close();
        }
    }

    @Override
    public void run() {

        long lastHeartbeat = 0;

        Main.status("Connecting...");

        Main.log("TunnelController started.");

        boolean connected = false;

        while (true) {

            long now = System.currentTimeMillis();

            if (maintenance && clients.isEmpty()) {
                Main.log("Tunnel empty, doing maintenance quit.");
                System.exit(0);
                return;
            }

            if (lastHeartbeat + 60000 < now && master != null && !maintenance) {
                Main.log("Sending a heartbeat to master server.");

                connected = false;
                try {
                    URL url = new URL(
                        master + "?version=2"
                        + "&name=" + URLEncoder.encode(name, "US-ASCII")
                        + "&password=" + (password == null ? "0" : "1")
                        + "&port=" + port
                        + "&clients=" + clients.size()
                        + "&maxclients=" + maxclients
                        + (masterpw != null ? "&masterpw=" + URLEncoder.encode(masterpw, "US-ASCII") : "")
                    );
                    HttpURLConnection con = (HttpURLConnection)url.openConnection();
                    con.setRequestMethod("GET");
                    con.setConnectTimeout(5000);
                    con.setReadTimeout(5000);
                    con.connect();
                    con.getInputStream().close();
                    con.disconnect();
                    connected = true;
                } catch (FileNotFoundException e) {
                    Main.log("Master server reported error 404.");
                } catch (MalformedURLException e) {
                    Main.log("Failed to send heartbeat: " + e.toString());
                } catch (IOException e) {
                    Main.log("Failed to send heartbeat: " + e.toString());
                }

                lastHeartbeat = now;
            }

            Set<Map.Entry<Short, Client>> set = clients.entrySet();

            for (Iterator<Map.Entry<Short, Client>> i = set.iterator(); i.hasNext();) {
                Map.Entry<Short, Client> e = i.next();
                Short id = e.getKey();
                Client client = e.getValue();

                if (client.getLastPacket() + 60000 < now) {
                    Main.log("Client " + e.getKey() +  " timed out.");
                    i.remove();
                    pool.add(id);
                }
            }

            Set<Map.Entry<String, Lock>> lset = locks.entrySet();

            for (Iterator<Map.Entry<String, Lock>> i = lset.iterator(); i.hasNext();) {
                Map.Entry<String, Lock> e = i.next();
                Lock l = e.getValue();

                if (l.firstRequest + 60000 < now) {
                    Main.log("Lock " + e.getKey() +  " released.");
                    i.remove();
                }
            }

            Main.status(
                (connected ? "Connected. " : "Disconnected from master. ") +
                clients.size() + " / " + maxclients + " players online."
            );

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

}
