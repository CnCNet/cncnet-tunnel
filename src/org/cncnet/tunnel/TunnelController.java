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
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

    public TunnelController(List<DatagramChannel> channels) {
        pool = new ArrayBlockingQueue<DatagramChannel>(channels.size(), true, channels);
        routers = new ConcurrentHashMap<DatagramChannel, Router>();
    }

    // will get called by another thread
    public Router getRouter(DatagramChannel channel) {
        return routers.get(channel);
    }

    private void handleRequest(HttpExchange t) throws IOException {

        Map<InetAddress, DatagramChannel> clients = new HashMap<InetAddress, DatagramChannel>();
        String params = t.getRequestURI().getQuery();
        List<InetAddress> addresses = new ArrayList<InetAddress>();

        if (params != null) {
            String[] pairs = params.split("&");
            for (int i = 0; i < pairs.length; i++) {
                String kv[] = pairs[i].split("=");
                if (kv.length != 2)
                    continue;

                kv[0] = URLDecoder.decode(kv[0], "UTF-8");
                kv[1] = URLDecoder.decode(kv[1], "UTF-8");

                if (!kv[0].equals("ip[]"))
                    throw new IOException("Invalid parameters.");

                InetAddress address = InetAddress.getByName(kv[1]);
                if (address != null) {
                    addresses.add(address);
                }
            }
        }

        if (addresses.size() < 2)
            throw new IOException("Empty game?");

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
            throw new IOException("Not enough free ports");
        }

        Router router = new Router(clients);

        for (DatagramChannel channel : clients.values()) {
            routers.put(channel, router);
        }

        t.sendResponseHeaders(200, ret.length());
        OutputStream os = t.getResponseBody();
        os.write(ret.toString().getBytes());
        os.close();
    }

    private void handleStatus(HttpExchange t) throws IOException {
        String response = pool.size() + " slots free.\n" + routers.size() + " slots in use.\n";
        System.out.println("Response: " + response);
        t.sendResponseHeaders(200, response.length());
        OutputStream os = t.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    @Override
    public void handle(HttpExchange t) throws IOException {
        String uri = t.getRequestURI().toString();
        t.getRequestBody().close();

        System.out.println("Request: " + uri);

        try {
            if (uri.startsWith("/request")) {
                handleRequest(t);
            } else if (uri.startsWith("/status")) {
                handleStatus(t);
            } else {
                t.sendResponseHeaders(400, 0);
            }
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
            String error = e.getMessage();
            t.sendResponseHeaders(500, error.length());
            OutputStream os = t.getResponseBody();
            os.write(error.getBytes());
            os.close();
        }
    }

    @Override
    public void run() {

        try {
            // setup our HTTP server
            HttpServer server = HttpServer.create(new InetSocketAddress(8000), 4);
            server.createContext("/request", this);
            server.createContext("/status", this);
            server.setExecutor(null);
            server.start();
        } catch (IOException e) {
            System.out.println("Failed to start HTTP server.");
            return;
        }

        System.out.println("TunnelController started.");

        while (true) {

            long now = System.currentTimeMillis();
            Set<Map.Entry<DatagramChannel, Router>> set = routers.entrySet();

            for (Iterator<Map.Entry<DatagramChannel, Router>> i = set.iterator(); i.hasNext();) {
                Map.Entry<DatagramChannel, Router> e = i.next();

                if (!e.getValue().inUse(now)) {
                    System.out.println("Found a timed out mapping.");
                    pool.add(e.getKey());
                    i.remove();
                }
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

}
