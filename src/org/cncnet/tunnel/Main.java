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

import com.sun.net.httpserver.HttpServer;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 *
 * @author Toni Spets <toni.spets@iki.fi>
 */
public class Main {

    static FileOutputStream logStream = null;

    // -name <str>          Custom name for the tunnel
    // -maxclients <num>    Maximum number of ports to allocate
    // -password <num>      Usage password
    // -firstport <num>     Ports are allocated from this up, first doubles as HTTP
    // -masterpw <str>      Optional password to send to master when registering
    // -nomaster            Don't register to master
    // -logfile <str>       Log everything to this file

    public static void main(String[] args) {

        String name = "Unnamed CnCNet 5 tunnel";
        int maxclients = 8;
        String password = null;
        int firstport = 50000;
        String master = "http://cncnet.org/master-announce";
        String masterpw = null;
        boolean nomaster = false;
        String logfile = null;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-name") && i < args.length - 1) {
                name = args[++i];
            } else if (args[i].equals("-maxclients") && i < args.length - 1) {
                maxclients = Math.max(Math.abs(Integer.parseInt(args[++i])), 2);
            } else if (args[i].equals("-password") && i < args.length - 1) {
                password = args[++i];
            } else if (args[i].equals("-firstport") && i < args.length - 1) {
                firstport = Math.max(Math.abs(Integer.parseInt(args[++i])), 1024);
            } else if (args[i].equals("-master") && i < args.length - 1) {
                master = args[++i];
            } else if (args[i].equals("-masterpw") && i < args.length - 1) {
                masterpw = args[++i];
            } else if (args[i].equals("-nomaster")) {
                nomaster = true;
            } else if (args[i].equals("-logfile") && i < args.length - 1) {
                logfile = args[++i];
            } else {
                Main.log("Unknown parameter: " + args[i]);
            }
        }

        firstport = Math.min(firstport, 65535 - maxclients);

        if (logfile != null) {
            try {
                logStream = new FileOutputStream(logfile, true);
            } catch (IOException e) {
                // silently ignore errors
            }
        }

        Main.log("CnCNet tunnel starting...");
        Main.log("Name       : " + name);
        Main.log("Max clients: " + maxclients);
        if (password != null)
            Main.log("Password   : " + password);
        Main.log("Ports      : " + firstport + " - " + (firstport + maxclients - 1) + " (HTTP server on " + firstport + ")");
        if (masterpw != null && !nomaster)
            Main.log("Master pass: " + masterpw);
        if (nomaster)
            Main.log("Master server disabled.");
        else
            Main.log("Master     : " + master);

        if (logStream != null) {
            Main.log("Logging to " + logfile);
        }

        try {
            Selector selector = Selector.open();
            List<DatagramChannel> channels = new ArrayList<DatagramChannel>();

            // do a static allocation of 100 ports for now
            for (int i = 0; i < maxclients; i++) {
                DatagramChannel channel = DatagramChannel.open();
                channel.configureBlocking(false);
                channel.socket().bind(new InetSocketAddress("0.0.0.0", firstport + i));
                channel.register(selector, SelectionKey.OP_READ);
                channels.add(channel);
            }

            TunnelController controller = new TunnelController(channels, name, password, firstport, maxclients, nomaster ? null : master, masterpw);

            // setup our HTTP server
            HttpServer server = HttpServer.create(new InetSocketAddress(firstport), 4);
            server.createContext("/request", controller);
            server.createContext("/status", controller);
            server.setExecutor(null);
            server.start();

            new Thread(controller).start();

            ByteBuffer buf = ByteBuffer.allocate(4096);

            while (true) {
                if (selector.select() > 0) {
                    for (Iterator<SelectionKey> i = selector.selectedKeys().iterator(); i.hasNext();) {
                        SelectionKey k = i.next();
                        DatagramChannel chan = (DatagramChannel)k.channel();

                        try {
                            buf.clear();
                            InetSocketAddress from = (InetSocketAddress)chan.receive(buf);
                            Router router = controller.getRouter(chan);
                            RouteResult res = (router == null ? null : router.route(from, chan));
                            if (res == null) {
                                //Main.log("Ignoring packet from " + from + " (routing failed), was " + buf.position() + " bytes");
                            } else {
                                //Main.log("Packet from " + from + " routed to " + res.getDestination() + ", was " + buf.position() + " bytes");
                                int len = buf.position();
                                buf.flip();
                                res.getChannel().send(buf, res.getDestination());
                            }
                        } catch (IOException e) {
                            Main.log("IOException when handling event: " + e.getMessage());
                        }

                        if (!k.channel().isOpen()) {
                            k.cancel();
                        }

                        i.remove();
                    }
                }
            }

        } catch (Exception e) {
            Main.log(e.toString());
        }
    }

    public static void log(String s) {
        for (String line : s.split("\n")) {
            String out = "[" + new Date().toString() + "] " + line + "\n";
            System.out.print(out);
            if (logStream != null) {
                try {
                    logStream.write(out.getBytes());
                } catch (IOException e) {

                }
            }
        }
    }
}
