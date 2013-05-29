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
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author Toni Spets <toni.spets@iki.fi>
 */
public class TunnelController implements HttpHandler, Runnable {

    private BlockingQueue<DatagramChannel> pool;
    private Map<DatagramChannel, Router> routers;
    private Map<String, Router> locks;

    private String name;
    private String password;
    private int maxclients;
    private int port;
    private String master;
    private String masterpw = null;

    public TunnelController(List<DatagramChannel> channels, String name, String password, int port, int maxclients, String master, String masterpw) {
        pool = new ArrayBlockingQueue<DatagramChannel>(channels.size(), true, channels);
        routers = new ConcurrentHashMap<DatagramChannel, Router>();
        locks = new ConcurrentHashMap<String, Router>();

        this.name = name;
        this.password = password;
        this.maxclients = maxclients;
        this.port = port;
        this.master = master;
        this.masterpw = masterpw;
    }

    // will get called by another thread
    public Router getRouter(DatagramChannel channel) {
        return routers.get(channel);
    }

    private void handleRequest(HttpExchange t) throws IOException {

        Map<InetAddress, DatagramChannel> clients = new HashMap<InetAddress, DatagramChannel>();
        String params = t.getRequestURI().getQuery();
        List<InetAddress> addresses = new ArrayList<InetAddress>();
        String requestAddress = t.getRemoteAddress().getAddress().getHostAddress();
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

            if (kv[0].equals("ip[]")) {
                InetAddress address = InetAddress.getByName(kv[1]);
                if (address != null) {
                    addresses.add(address);
                }
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

        if (addresses.size() < 2 || addresses.size() > 8) {
            // Bad Request
            Main.log("Request had invalid amount of addresses.");
            t.sendResponseHeaders(400, 0);
            t.getResponseBody().close();
            return;
        }

        // lock the request ip out until this router is collected
        if (locks.containsKey(requestAddress)) {
            // Too Many Requests
            Main.log("Same address tried to request more than one active router.");
            t.sendResponseHeaders(429, 0);
            t.getResponseBody().close();
            return;
        }

        StringBuilder ret = new StringBuilder();

        try {
            for (InetAddress address : addresses) {
                DatagramChannel channel = pool.remove();
                ret.append(address.toString().substring(1) + " " + channel.socket().getLocalPort() + "\n");
                clients.put(address, channel);
            }
        } catch (NoSuchElementException e) {
            // if not enough 
            pool.addAll(clients.values());
            // Service Unavailable
            Main.log("Request wanted more than we could provide.");
            t.sendResponseHeaders(503, 0);
            t.getResponseBody().close();
            return;
        }

        Router router = new Router(clients);
        router.setAttachment(requestAddress);

        // lock the request ip out until this router is collected
        locks.put(t.getRemoteAddress().getAddress().getHostAddress(), router);

        Set<Entry<InetAddress, DatagramChannel>> entries = clients.entrySet();

        for (Entry<InetAddress, DatagramChannel> entry : entries) {
            DatagramChannel channel = entry.getValue();
            InetAddress address = entry.getKey();
            Main.log("Port " + channel.socket().getLocalPort() + " allocated for " + address.toString() + " in router " + router.hashCode() + ".");
            routers.put(channel, router);
        }

        t.sendResponseHeaders(200, ret.length());
        OutputStream os = t.getResponseBody();
        os.write(ret.toString().getBytes());
        os.close();
    }

    private void handleStatus(HttpExchange t) throws IOException {
        String response = pool.size() + " slots free.\n" + routers.size() + " slots in use.\n";
        Main.log("Response: " + response);
        t.sendResponseHeaders(200, response.length());
        OutputStream os = t.getResponseBody();
        os.write(response.getBytes());
        os.close();
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

        Main.log("TunnelController started.");

        while (true) {

            long now = System.currentTimeMillis();

            if (lastHeartbeat + 60000 < now && master != null) {
                Main.log("Sending a heartbeat to master server.");

                try {
                    URL url = new URL(
                        master + "?"
                        + "name=" + URLEncoder.encode(name, "US-ASCII")
                        + "&password=" + (password == null ? "0" : "1")
                        + "&port=" + port
                        + "&clients=" + routers.size()
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
                } catch (FileNotFoundException e) {
                    Main.log("Master server reported error 404.");
                } catch (MalformedURLException e) {
                    Main.log("Failed to send heartbeat: " + e.toString());
                } catch (IOException e) {
                    Main.log("Failed to send heartbeat: " + e.toString());
                }

                lastHeartbeat = now;
            }

            Set<Map.Entry<DatagramChannel, Router>> set = routers.entrySet();

            for (Iterator<Map.Entry<DatagramChannel, Router>> i = set.iterator(); i.hasNext();) {
                Map.Entry<DatagramChannel, Router> e = i.next();
                Router router = e.getValue();

                if (router.getLastPacket() + 60000 < now) {
                    DatagramChannel channel = e.getKey();
                    Main.log("Port " + channel.socket().getLocalPort() +  " timed out from router " + router.hashCode() + ".");
                    pool.add(channel);
                    i.remove();

                    if (locks.containsKey(router.getAttachment())) {
                        locks.remove(router.getAttachment());
                    }
                }
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

}
