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

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 *
 * @author Toni Spets <toni.spets@iki.fi>
 */
public class StatusWindow extends JFrame {

    private JTextArea logArea;
    private JLabel statusLabel;

    public StatusWindow() {

        setTitle("CnCNet Tunnel");
        setIconImage(new ImageIcon(Main.class.getResource("res/cncnet-icon.png")).getImage());

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

        logArea = new JTextArea();
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setBorder(new EmptyBorder(2, 2, 2, 2));
        logArea.setEditable(false);
        logArea.setAutoscrolls(true);
        JScrollPane logPane = new JScrollPane(logArea);
        logPane.setAlignmentX(LEFT_ALIGNMENT);

        mainPanel.add(logPane);

        JPanel statusPanel = new JPanel();
        statusPanel.setLayout(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        statusPanel.setAlignmentX(LEFT_ALIGNMENT);
        statusPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));

        statusLabel = new JLabel(" ");
        statusLabel.setBorder(new EmptyBorder(2, 2, 2, 2));
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        statusPanel.add(statusLabel);

        mainPanel.add(statusPanel);

        this.add(mainPanel);
        this.setSize(600, 200);
    }

    public void log(final String str) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (logArea.getText().length() == 0) {
                    logArea.append(str);
                } else {
                    logArea.append("\n" + str);
                }
            }
        });
    }

    public void status(final String str) {

        final JFrame window = this;

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                statusLabel.setText(str);
                window.setTitle("CnCNet Tunnel - " + str);
            }
        });
    }
}
