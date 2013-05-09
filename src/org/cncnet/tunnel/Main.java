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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 *
 * @author Toni Spets <toni.spets@iki.fi>
 */
public class Main {
    public static void main(String[] args) {
        try {
            Map<InetAddress, DatagramChannel> clients = new HashMap<>();

            DatagramChannel ch1 = DatagramChannel.open();
            ch1.bind(new InetSocketAddress("0.0.0.0", 50000));
            DatagramChannel ch2 = DatagramChannel.open();
            ch2.bind(new InetSocketAddress("0.0.0.0", 50001));
            DatagramChannel ch3 = DatagramChannel.open();
            ch3.bind(new InetSocketAddress("0.0.0.0", 50002));

            clients.put(InetAddress.getByName("1.1.1.1"), ch1);
            clients.put(InetAddress.getByName("2.2.2.2"), ch2);
            clients.put(InetAddress.getByName("3.3.3.3"), ch3);

            Router router = new Router(clients);

            Selector selector = Selector.open();

            for (DatagramChannel chan : clients.values()) {
                chan.configureBlocking(false);
                chan.register(selector, SelectionKey.OP_READ);
            }

            ByteBuffer buf = ByteBuffer.allocate(4096);

            while (true) {
                if (selector.select() > 0) {
                    for (Iterator<SelectionKey> i = selector.selectedKeys().iterator(); i.hasNext();) {
                        SelectionKey k = i.next();
                        DatagramChannel chan = (DatagramChannel)k.channel();

                        try {
                            buf.clear();
                            InetSocketAddress from = (InetSocketAddress)chan.receive(buf);
                            RouteResult res = router.route(from, chan);
                            if (res == null) {
                                System.out.println("Ignoring packet from " + from + " (routing failed), was " + buf.position() + " bytes");
                            } else {
                                System.out.println("Packet from " + from + " routed to " + res.getDestination() + ", was " + buf.position() + " bytes");
                                int len = buf.position();
                                buf.flip();
                                if (res.getChannel().send(buf, res.getDestination()) < len) {
                                    System.out.println("  PACKET WAS DROPPED");
                                }
                            }
                        } catch (IOException e) {
                            System.out.println("IOException when handling event: " + e.getMessage());
                        }

                        if (!k.channel().isOpen()) {
                            k.cancel();
                        }

                        i.remove();
                    }
                }
            }

        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
