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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 *
 * @author Toni Spets <toni.spets@iki.fi>
 */
public class Main {
    public static void main(String[] args) {
        try {
            Selector selector = Selector.open();
            List<DatagramChannel> channels = new ArrayList<DatagramChannel>();

            // do a static allocation of 100 ports for now
            for (int i = 50000; i < 50100; i++) {
                DatagramChannel channel = DatagramChannel.open();
                channel.configureBlocking(false);
                channel.socket().bind(new InetSocketAddress("0.0.0.0", i));
                channel.register(selector, SelectionKey.OP_READ);
                channels.add(channel);
            }

            TunnelController controller = new TunnelController(channels);
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
