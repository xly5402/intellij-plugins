/*
 * Copyright 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.jstestdriver.idea.server.ui;

import com.google.jstestdriver.BrowserInfo;
import com.google.jstestdriver.ServerStartupAction;
import com.google.jstestdriver.hooks.ServerListener;
import com.google.jstestdriver.idea.MessageBundle;
import com.google.jstestdriver.idea.PluginResources;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.PlatformIcons;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;

/**
 * @author alexeagle@google.com (Alex Eagle)
 */
public class ToolPanel extends SimpleToolWindowPanel {

  private static final Logger LOG = Logger.getInstance(ToolPanel.class);
  private static final String PLACE = "JsTestDriverToolbar";

  // TODO - make configurable
  public final static int serverPort = 9876;
  static ServerStartupAction myServerStartupAction;
  public static final JstdServerState SHARED_STATE = new JstdServerState();

  private final JTextField myCaptureUrlTextField;

  public ToolPanel() {
    super(false, true);

    final StatusBar statusBar = new StatusBar(MessageBundle.getBundle());
    final CapturedBrowsersPanel capturedBrowsersPanel = new CapturedBrowsersPanel();
    myCaptureUrlTextField = createCaptureUrlTextField();

    ActionToolbar actionToolbar = createActionToolbar();
    setToolbar(actionToolbar.getComponent());

    final Pair<JPanel, ActionToolbar> captureUrlInfo = createCaptureUrlInfo(myCaptureUrlTextField);

    List<ServerListener> myServerListeners = Arrays.asList(
        statusBar,
        capturedBrowsersPanel,
        new LocalManager(new ActionToolbar[] { actionToolbar, captureUrlInfo.second }, myCaptureUrlTextField)
    );
    for (ServerListener serverListener : myServerListeners) {
      if (SHARED_STATE.isServerRunning()) {
        serverListener.serverStarted();
        for (BrowserInfo browserInfo : SHARED_STATE.getCapturedBrowsers()) {
          serverListener.browserCaptured(browserInfo);
        }
      } else {
        serverListener.serverStopped();
      }
      SHARED_STATE.addServerListener(serverListener);
    }

    setBackground(UIUtil.getTreeTextBackground());
    JPanel content = new JPanel() {{
      setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

      statusBar.setBorder(BorderFactory.createLineBorder(Color.GRAY));
      add(statusBar);

      JPanel captureUrlPanel = captureUrlInfo.first;
      captureUrlPanel.setBorder(BorderFactory.createEmptyBorder(0, 5, 10, 0));
      add(captureUrlPanel);

      add(capturedBrowsersPanel);
    }};
    setContent(minimizeHeight(content));
  }

  @NotNull
  private static Pair<JPanel, ActionToolbar> createCaptureUrlInfo(@NotNull final JTextField captureUrlTextField) {
    CopyAction copyAction = new CopyAction(captureUrlTextField, captureUrlTextField);
    DefaultActionGroup actionGroup = new DefaultActionGroup();
    actionGroup.add(copyAction);

    ActionPopupMenu actionPopupMenu = ActionManager.getInstance().createActionPopupMenu(PLACE, actionGroup);
    JPopupMenu popupMenu = actionPopupMenu.getComponent();
    captureUrlTextField.setComponentPopupMenu(popupMenu);

    JLabel title = new JLabel(PluginResources.getCaptureUrlMessage());
    ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(PLACE, actionGroup, true);

    JPanel panel = new JPanel(new GridBagLayout());
    panel.add(title, new GridBagConstraints(
        0, 0,
        1, 1,
        0.0, 0.0,
        GridBagConstraints.CENTER,
        GridBagConstraints.NONE,
        new Insets(0, 0, 0, 0),
        0, 0
    ));
    panel.add(captureUrlTextField, new GridBagConstraints(
        1, 0,
        1, 1,
        1.0, 0.0,
        GridBagConstraints.CENTER,
        GridBagConstraints.HORIZONTAL,
        new Insets(0, 0, 0, 0),
        0, 0
    ));
    JComponent actionToolbarComponent = actionToolbar.getComponent();
    panel.add(actionToolbarComponent, new GridBagConstraints(
        2, 0,
        1, 1,
        0.0, 0.0,
        GridBagConstraints.CENTER,
        GridBagConstraints.NONE,
        new Insets(0, 0, 0, 0),
        0, 0
    ));
    return Pair.create(panel, actionToolbar);
  }

  private static JComponent minimizeHeight(@NotNull JComponent component) {
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(component, BorderLayout.NORTH);
    return panel;
  }

  private static ActionToolbar createActionToolbar() {
    DefaultActionGroup actionGroup = new DefaultActionGroup();
    actionGroup.add(new ServerStartAction(SHARED_STATE));
    actionGroup.add(new ServerStopAction(SHARED_STATE));
    return ActionManager.getInstance().createActionToolbar(PLACE, actionGroup, false);
  }

  public JComponent getPreferredFocusedComponent() {
    return myCaptureUrlTextField;
  }

  private static JTextField createCaptureUrlTextField() {
    final JTextField textField = new JTextField();
    textField.setEditable(false);
    textField.setBackground(Color.WHITE);
    textField.getCaret().setVisible(true);
    textField.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(final MouseEvent e) {
        if (e.getClickCount() == 2) {
          textField.selectAll();
        }
      }
    });
    textField.addFocusListener(new FocusAdapter() {
      @Override
      public void focusGained(FocusEvent e) {
        textField.selectAll();
      }
    });
    return textField;
  }

  private static class LocalManager implements ServerListener {

    private final ActionToolbar[] myActionToolbars;
    private final JTextField myCaptureUrl;

    private LocalManager(@NotNull ActionToolbar[] actionToolbars, @NotNull JTextField captureUrl) {
      myActionToolbars = actionToolbars;
      myCaptureUrl = captureUrl;
    }

    @Override
    public void serverStarted() {
      serverStatusChanged(true);
    }

    @Override
    public void serverStopped() {
      serverStatusChanged(false);
    }

    private void serverStatusChanged(final boolean started) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        @Override
        public void run() {
          if (started) {
            String serverUrl = MessageFormat.format("http://{0}:{1,number,###}/capture",
                                                    getHostName(), serverPort);
            myCaptureUrl.setText(serverUrl);
            myCaptureUrl.requestFocusInWindow();
            myCaptureUrl.selectAll();
          } else {
            myCaptureUrl.setText("");
          }
          for (ActionToolbar actionToolbar : myActionToolbars) {
            actionToolbar.updateActionsImmediately();
          }
        }
      });
    }

    public static String getHostName() {
      try {
        InetAddress address = InetAddress.getByName(null);
        return address.getHostName();
      } catch (UnknownHostException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void browserCaptured(BrowserInfo info) {}

    @Override
    public void browserPanicked(BrowserInfo info) {}
  }

  private static class CopyAction extends AnAction {

    private final JTextField myCaptureUrlTextField;

    private CopyAction(@NotNull JTextField captureUrlTextField, @NotNull JComponent parent) {
      super("Copy", "Copy", PlatformIcons.COPY_ICON);
      myCaptureUrlTextField = captureUrlTextField;
      ShortcutSet shortcutSet = new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_MASK));
      registerCustomShortcutSet(shortcutSet, parent);
    }

    @Override
    public void update(AnActionEvent e) {
      e.getPresentation().setEnabled(StringUtil.isNotEmpty(myCaptureUrlTextField.getText()));
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      System.out.println("Jstd copyAction performed");
      String url = myCaptureUrlTextField.getText();
      new ClipboardCopier().toClipboard(url);
    }
  }

  private static class ClipboardCopier implements ClipboardOwner {

    public void toClipboard(String value) {
      SecurityManager sm = System.getSecurityManager();
      if (sm != null) {
        try {
          sm.checkSystemClipboardAccess();
        } catch (Exception e) {
          LOG.warn("[JsTestDriver] Can't copy capture url: no access to system clipboard", e);
          return;
        }
      }
      Toolkit tk = Toolkit.getDefaultToolkit();
      StringSelection st = new StringSelection(value);
      Clipboard cp = tk.getSystemClipboard();
      cp.setContents(st, this);
    }

    public void lostOwnership(Clipboard clipboard, Transferable contents) {
      // this doesn't seem to be important
    }
  }

}
