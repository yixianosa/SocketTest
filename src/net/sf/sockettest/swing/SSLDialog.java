package net.sf.sockettest.swing;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import javax.swing.*;
import javax.swing.border.EtchedBorder;
import javax.swing.border.TitledBorder;
import net.sf.sockettest.SSLConfig;
import net.sf.sockettest.Util;

/**
 * SSL/TLS configuration dialog for mutual TLS (mTLS) support.
 * Supports keystore/truststore file selection and password input.
 * Used by both TCP Client and TCP Server panels.
 */
public class SSLDialog extends JDialog {

    private final SSLConfig config;
    private boolean confirmed = false;
    private final boolean serverMode;

    private JCheckBox enableCheckBox = new JCheckBox("Enable SSL/TLS");

    private JTextField keyStoreField = new JTextField();
    private JButton keyStoreBrowseButton = new JButton("Browse");
    private JPasswordField keyStorePassField = new JPasswordField();

    private JTextField trustStoreField = new JTextField();
    private JButton trustStoreBrowseButton = new JButton("Browse");
    private JPasswordField trustStorePassField = new JPasswordField();

    private JCheckBox needClientAuthCheckBox = new JCheckBox("Require Client Certificate (mTLS, Server only)");

    public SSLDialog(JFrame parent, String title, SSLConfig config, boolean serverMode) {
        super(parent, title, true);
        this.config = config;
        this.serverMode = serverMode;

        Container cp = getContentPane();
        cp.setLayout(new BorderLayout(10, 10));

        JPanel mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // Enable SSL checkbox
        gbc.gridx = 0;
        gbc.gridy = 0;
            gbc.gridwidth = 3;
        enableCheckBox.setMnemonic('E');
        mainPanel.add(enableCheckBox, gbc);

        // KeyStore panel
        gbc.gridy = 1;
            gbc.gridwidth = 3;
        TitledBorder ksBorder = BorderFactory.createTitledBorder(
                new EtchedBorder(), "KeyStore (Local certificate + private key)");
        JPanel ksPanel = new JPanel(new GridBagLayout());
        ksPanel.setBorder(ksBorder);

        GridBagConstraints kgbc = new GridBagConstraints();
        kgbc.insets = new Insets(2, 3, 2, 3);
        kgbc.fill = GridBagConstraints.HORIZONTAL;

        kgbc.gridx = 0;
        kgbc.gridy = 0;
        kgbc.weightx = 0.0;
        ksPanel.add(new JLabel("File:"), kgbc);

        kgbc.gridx = 1;
        kgbc.weightx = 1.0;
        ksPanel.add(keyStoreField, kgbc);

        kgbc.gridx = 2;
        kgbc.weightx = 0.0;
        keyStoreBrowseButton.setMnemonic('K');
        keyStoreBrowseButton.addActionListener(e -> browseFile(keyStoreField, "Select KeyStore"));
        ksPanel.add(keyStoreBrowseButton, kgbc);

        kgbc.gridx = 0;
        kgbc.gridy = 1;
        kgbc.weightx = 0.0;
        ksPanel.add(new JLabel("Password:"), kgbc);

        kgbc.gridx = 1;
        kgbc.gridwidth = 2;
        kgbc.weightx = 1.0;
        ksPanel.add(keyStorePassField, kgbc);

        mainPanel.add(ksPanel, gbc);

        // TrustStore panel
        gbc.gridy = 2;
        TitledBorder tsBorder = BorderFactory.createTitledBorder(
                new EtchedBorder(), "TrustStore (Peer certificate CA)");
        JPanel tsPanel = new JPanel(new GridBagLayout());
        tsPanel.setBorder(tsBorder);

        GridBagConstraints tgbc = new GridBagConstraints();
        tgbc.insets = new Insets(2, 3, 2, 3);
        tgbc.fill = GridBagConstraints.HORIZONTAL;

        tgbc.gridx = 0;
        tgbc.gridy = 0;
        tgbc.weightx = 0.0;
        tsPanel.add(new JLabel("File:"), tgbc);

        tgbc.gridx = 1;
        tgbc.weightx = 1.0;
        tsPanel.add(trustStoreField, tgbc);

        tgbc.gridx = 2;
        tgbc.weightx = 0.0;
        trustStoreBrowseButton.setMnemonic('T');
        trustStoreBrowseButton.addActionListener(e -> browseFile(trustStoreField, "Select TrustStore"));
        tsPanel.add(trustStoreBrowseButton, tgbc);

        tgbc.gridx = 0;
        tgbc.gridy = 1;
        tgbc.weightx = 0.0;
        tsPanel.add(new JLabel("Password:"), tgbc);

        tgbc.gridx = 1;
        tgbc.gridwidth = 2;
        tgbc.weightx = 1.0;
        tsPanel.add(trustStorePassField, tgbc);

        mainPanel.add(tsPanel, gbc);

        // Require client certificate checkbox (server only)
        if (serverMode) {
            gbc.gridy = 3;
            gbc.gridwidth = 3;
            needClientAuthCheckBox.setToolTipText("Require connecting clients to provide a certificate");
            mainPanel.add(needClientAuthCheckBox, gbc);
        }

        // Button panel
        JPanel buttonPanel = new JPanel();
        JButton okButton = new JButton("OK");
        okButton.setMnemonic('O');
        okButton.addActionListener(e -> {
            saveToConfig();
            confirmed = true;
            dispose();
        });

        JButton cancelButton = new JButton("Cancel");
        cancelButton.setMnemonic('C');
        cancelButton.addActionListener(e -> dispose());

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);

        JPanel rootPanel = new JPanel(new BorderLayout(10, 10));
        rootPanel.add(mainPanel, BorderLayout.CENTER);
        rootPanel.add(buttonPanel, BorderLayout.SOUTH);
        rootPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        cp.add(rootPanel);

        loadFromConfig();

        pack();
        setSize(520, getPreferredSize().height + 20);
        Util.centerWindow(this);
    }

    private void browseFile(JTextField field, String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        if (field.getText() != null && !field.getText().trim().isEmpty()) {
            chooser.setSelectedFile(new File(field.getText().trim()));
        }
        int ret = chooser.showOpenDialog(this);
        if (ret == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void loadFromConfig() {
        enableCheckBox.setSelected(config.isEnabled());
        if (config.getKeyStorePath() != null) keyStoreField.setText(config.getKeyStorePath());
        if (config.getKeyStorePassword() != null) keyStorePassField.setText(config.getKeyStorePassword());
        if (config.getTrustStorePath() != null) trustStoreField.setText(config.getTrustStorePath());
        if (config.getTrustStorePassword() != null) trustStorePassField.setText(config.getTrustStorePassword());
        needClientAuthCheckBox.setSelected(config.isNeedClientAuth());
    }

    private void saveToConfig() {
        config.setEnabled(enableCheckBox.isSelected());
        config.setKeyStorePath(keyStoreField.getText().trim());
        config.setKeyStorePassword(new String(keyStorePassField.getPassword()));
        config.setTrustStorePath(trustStoreField.getText().trim());
        config.setTrustStorePassword(new String(trustStorePassField.getPassword()));
        config.setNeedClientAuth(needClientAuthCheckBox.isSelected());
    }

    /**
     * Show dialog and return whether user clicked OK.
     */
    public boolean showDialog() {
        setVisible(true);
        return confirmed;
    }

    /**
     * Get the underlying SSLConfig. Only valid when showDialog() returned true.
     */
    public SSLConfig getConfig() {
        return config;
    }
}
