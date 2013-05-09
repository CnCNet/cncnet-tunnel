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

import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;

/**
 *
 * @author Toni Spets <toni.spets@iki.fi>
 */
public class RouteResult {
    private InetSocketAddress destination;
    private DatagramChannel channel;

    public RouteResult(InetSocketAddress destination, DatagramChannel channel) {
        this.destination = destination;
        this.channel = channel;
    }

    public InetSocketAddress getDestination() {
        return destination;
    }

    public DatagramChannel getChannel() {
        return channel;
    }
}
