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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Toni Spets <toni.spets@iki.fi>
 */
public class Router {
    private Map<DatagramChannel, Port> portMap;
    private Map<InetAddress, DatagramChannel> ipMap;
    private long lastPacket;

    public Router(Map<InetAddress, DatagramChannel> ipMap) {
        this.portMap = new HashMap<DatagramChannel, Port>();
        this.ipMap = new HashMap<InetAddress, DatagramChannel>(ipMap);
        this.lastPacket = System.currentTimeMillis();

        List<DatagramChannel> chanList = new ArrayList<DatagramChannel>();
        for (DatagramChannel port : ipMap.values()) {
            chanList.add(port);
        }

        for (Map.Entry<InetAddress, DatagramChannel> entry : ipMap.entrySet()) {
            portMap.put(entry.getValue(), new Port(entry.getKey(), chanList));
        }
    }

    public long getLastPacket() {
        return lastPacket;
    }

    public RouteResult route(InetSocketAddress source, DatagramChannel channel) {
        return route(source, channel, System.currentTimeMillis());
    }

    public RouteResult route(InetSocketAddress source, DatagramChannel channel, long now) {
        Port inPort = portMap.get(channel);

        if (inPort == null) {
            return null;
        }

        DatagramChannel outChannel = ipMap.get(source.getAddress());

        if (outChannel == null) {
            return null;
        }

        Port outPort = portMap.get(outChannel);
        outPort.setRoute(channel, source.getPort());

        InetAddress dstIp = inPort.getIp();
        int dstPort = inPort.getRoute(outChannel);

        if (dstPort == 0) {
            return null;
        }

        lastPacket = now;
        return new RouteResult(new InetSocketAddress(dstIp, dstPort), outChannel);
    }
}
