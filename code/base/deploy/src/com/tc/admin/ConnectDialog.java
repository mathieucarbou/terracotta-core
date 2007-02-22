/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin;

import org.dijon.Button;
import org.dijon.Container;
import org.dijon.Dialog;
import org.dijon.DialogResource;
import org.dijon.TextField;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXServiceURL;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public class ConnectDialog extends Dialog {

  private JMXServiceURL        m_url;
  private Map                  m_env;
  private long                 m_timeout;
  private ConnectionListener   m_listener;
  private JMXConnector         m_jmxc;
  private Thread               m_mainThread;
  private Thread               m_connectThread;
  private Timer                m_timer;
  private Exception            m_error;
  private Button               m_cancelButton;
  private final JTextField     m_usernameField;
  private final JPasswordField m_passwordField;
  private final Button         m_okButton;
  private final Container      m_emptyPanel;
  private final Container      m_authPanel;

  public ConnectDialog(Frame parent, JMXServiceURL url, Map env, long timeout, ConnectionListener listener) {
    super(parent, true);

    m_url = url;
    m_env = env;
    m_timeout = timeout;
    m_listener = listener;

    AdminClientContext acc = AdminClient.getContext();
    load((DialogResource) acc.topRes.child("ConnectDialog"));
    pack();

    m_cancelButton = (Button) findComponent("CancelButton");
    m_cancelButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent ae) {
        m_cancelButton.setEnabled(false);
        m_mainThread.interrupt();
        m_jmxc = null;
        ConnectDialog.this.setVisible(false);
      }
    });
    getContentPane().addHierarchyListener(new HL());

    int delay = 1000;
    ActionListener taskPerformer = new ActionListener() {
      public void actionPerformed(ActionEvent evt) {
        setVisible(false);
      }
    };

    m_emptyPanel = (Container) findComponent("EmptyPanel");
    m_emptyPanel.setLayout(new BorderLayout());

    m_authPanel = (Container) AdminClient.getContext().topRes.resolve("AuthPanel");
    Container credentialsPanel = (Container) m_authPanel.findComponent("CredentialsPanel");
    m_authPanel.setVisible(false);
    this.m_usernameField = (JTextField) credentialsPanel.findComponent("UsernameField");
    this.m_okButton = (Button) m_authPanel.findComponent("OKButton");

    // must be found last because JPasswordField is not a Dijon Component
    TextField passwordField = (TextField) credentialsPanel.findComponent("PasswordField");
    Container passwdHolder = new Container();
    passwdHolder.setLayout(new BorderLayout());
    passwdHolder.add(m_passwordField = new JPasswordField());
    credentialsPanel.replaceChild(passwordField, passwdHolder);

    m_okButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent ae) {
        final String username = m_usernameField.getText().trim();
        final String password = new String(m_passwordField.getPassword()).trim();
        SwingUtilities.invokeLater(new Thread() {
          public void run() {
            ((AuthenticatingJMXConnector) m_jmxc).handleOkClick(username, password);
          }
        });
      }
    });

    m_timer = new Timer(delay, taskPerformer);
    m_timer.setRepeats(false);
  }

  private void disableAuthenticationDialog() {
    m_usernameField.setEnabled(false);
    m_passwordField.setEnabled(false);
    m_emptyPanel.removeAll();
    m_usernameField.setText("");
    m_passwordField.setText("");
    m_authPanel.setVisible(false);
    pack();
  }

  private void enableAuthenticationDialog() {
    m_emptyPanel.add(m_authPanel);
    m_usernameField.setEnabled(true);
    m_passwordField.setEnabled(true);
    m_authPanel.setVisible(true);
    pack();
  }

  public void setServiceURL(JMXServiceURL url) {
    m_url = url;
  }

  public JMXServiceURL getServiceURL() {
    return m_url;
  }

  public void setEnvironment(Map env) {
    m_env = env;
  }

  public Map getEnvironment() {
    return m_env;
  }

  public void setTimeout(long millis) {
    m_timeout = millis;
  }

  public long getTimeout() {
    return m_timeout;
  }

  public void setConnectionListener(ConnectionListener listener) {
    m_listener = listener;
  }

  public ConnectionListener getConnectionListener() {
    return m_listener;
  }

  public JMXConnector getConnector() {
    return m_jmxc;
  }

  public Exception getError() {
    return m_error;
  }

  class HL implements HierarchyListener {
    public void hierarchyChanged(HierarchyEvent e) {
      long flags = e.getChangeFlags();

      if ((flags & HierarchyEvent.SHOWING_CHANGED) != 0) {
        if (isShowing()) {
          m_cancelButton.setEnabled(true);
          m_mainThread = new MainThread();
          m_mainThread.start();
        } else {
          fireHandleConnect();
        }
      }
    }
  }

  protected void fireHandleConnect() {
    if (m_listener != null) {
      try {
        if (m_error == null) {
          m_listener.handleConnection();
        } else {
          m_listener.handleException();
        }
      } catch (RuntimeException rte) {
        rte.printStackTrace();
      }
    }
  }

  class MainThread extends Thread {
    public void run() {
      m_connectThread = new ConnectThread();

      try {
        m_error = null;
        m_jmxc = new AuthenticatingJMXConnector(m_url, m_env);
        ((AuthenticatingJMXConnector) m_jmxc)
            .addAuthenticationListener(new AuthenticatingJMXConnector.AuthenticationListener() {
              public void handleEvent() {
                enableAuthenticationDialog();
              }
            });
        ((AuthenticatingJMXConnector) m_jmxc)
            .addCollapseListener(new AuthenticatingJMXConnector.AuthenticationListener() {
              public void handleEvent() {
                disableAuthenticationDialog();
              }
            });

        if (m_jmxc != null && m_error == null) {
          m_connectThread.start();
          // m_connectThread.join(m_timeout); XXX
          m_connectThread.join();
        }
      } catch (IOException e) {
        m_error = e;
      } catch (InterruptedException e) {
        m_connectThread.interrupt();
        disableAuthenticationDialog();
        m_error = new InterruptedIOException("Interrupted");
        return;
      }

      if (m_error == null && m_connectThread.isAlive()) {
        m_connectThread.interrupt();
        m_error = new InterruptedIOException("Connection timed out");
      }

      if (m_error != null) {
        m_connectThread.interrupt();
      }

      m_timer.start();
    }
  }

  class ConnectThread extends Thread {
    
    public ConnectThread() {
      setDaemon(true);
    }
    
    public void run() {
      try {
        m_jmxc.connect(m_env);
      } catch (IOException e) {
        m_error = e;
      } catch (RuntimeException e) {
        m_error = e;
      }
    }
  }

  void tearDown() {
    if (m_env != null) {
      m_env.clear();
    }

    m_url = null;
    m_env = null;
    m_listener = null;
    m_jmxc = null;
    m_mainThread = null;
    m_connectThread = null;
    m_cancelButton = null;
    m_timer = null;
    m_error = null;
  }
}
