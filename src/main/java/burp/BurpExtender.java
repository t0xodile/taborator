package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.collaborator.CollaboratorClient;
import burp.api.montoya.collaborator.CollaboratorPayload;
import burp.api.montoya.collaborator.Interaction;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.hotkey.HotKey;
import burp.api.montoya.ui.hotkey.HotKeyContext;
import burp.api.montoya.ui.hotkey.HotKeyHandler;
import burp.api.montoya.ui.hotkey.HotKeyEvent;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.api.montoya.ui.editor.RawEditor;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.core.ByteArray;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.*;
import java.nio.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BurpExtender implements BurpExtension, HttpHandler, ContextMenuItemsProvider {
    private String extensionName = "Taborator";
    private String extensionVersion = "2.1.6";
    private int maxHashMapSize = 10000;
    private MontoyaApi api;
    private JPanel panel;
    private volatile boolean running;
    private int unread = 0;
    private ArrayList<Integer> readRows = new ArrayList<>();
    private CollaboratorClient collaborator = null;
    private Map<Integer, HashMap<String, String>> interactionHistory = new ConcurrentHashMap<>();
    private Map<String, HashMap<String, String>> originalRequests = new ConcurrentHashMap<>();
    private Map<String, String> originalResponses = new ConcurrentHashMap<>();
    private JTabbedPane interactionsTab;
    private Integer selectedRow = -1;
    private Map<Integer, Color> colours = new ConcurrentHashMap<>();
    private Map<Integer, Color> textColours = new ConcurrentHashMap<>();
    private Map<Integer, String> comments = new ConcurrentHashMap<>();
    private static final String COLLABORATOR_PLACEHOLDER = "$collabplz";
    private static final Pattern COLLABORATOR_PLACEHOLDER_PATTERN = Pattern.compile(COLLABORATOR_PLACEHOLDER.replace("$", "\\$"));
    private static final Pattern SMTP_RCPT_TO_PATTERN = Pattern.compile("^RCPT TO:(.+?)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern SMTP_MAIL_FROM_PATTERN = Pattern.compile("^MAIL From:(.+)?$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern SMTP_DATA_PATTERN = Pattern.compile("^DATA[\\r\\n]+([\\d\\D]+)?[\\r\\n]+[.][\\r\\n]+", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern BRACKETED_ADDRESS_PATTERN = Pattern.compile("<(.*)>");
    private Thread pollThread;
    private long POLL_EVERY_MS = 10000;
    private boolean pollNow = false;
    private boolean createdCollaboratorPayload = false;
    private int pollCounter = 0;
    private volatile boolean shutdown = false;
    private volatile boolean isSleeping = false;
    private TaboratorSettings settings;
    private Integer rowNumber = 0;
    private DefaultTableModel model;
    private JTable collaboratorTable;
    private TableRowSorter<TableModel> sorter = null;
    private Color defaultTabColour;
    @Override
    public void initialize(MontoyaApi api) {
        shutdown = false;
        isSleeping = false;
        this.api = api;
        api.http().registerHttpHandler(this);
        api.userInterface().registerContextMenuItemsProvider(this);
        
        // Register hotkeys for command palette - the actual keys are irrelevant, just want them in command pallet.
        HotKey insertCollabPayload = HotKey.hotKey("Insert Collaborator Payload", "Ctrl+Shift+Alt+C");

        HotKeyHandler insertCollabPayloadHandler = event -> event.messageEditorRequestResponse().ifPresent(editor -> {
            insertCollaboratorPayload(event);
        });

        api.userInterface().registerHotKeyHandler(HotKeyContext.HTTP_MESSAGE_EDITOR, insertCollabPayload, insertCollabPayloadHandler);

        HotKey insertCollabPlaceholder = HotKey.hotKey("Insert Placeholder", "Ctrl+Shift+Alt+P");

        HotKeyHandler insertCollabPlaceholderHandler = event -> event.messageEditorRequestResponse().ifPresent(editor -> {
            insertPlaceholder(event);
        });

        api.userInterface().registerHotKeyHandler(HotKeyContext.HTTP_MESSAGE_EDITOR, insertCollabPlaceholder, insertCollabPlaceholderHandler);

        api.extension().setName(extensionName);
        api.extension().registerUnloadingHandler(this::extensionUnloaded);
        defaultTabColour = getDefaultTabColour();
        settings = new TaboratorSettings(api);
        SwingUtilities.invokeLater(() -> {
                api.logging().logToOutput(extensionName + " " + extensionVersion);
                api.logging().logToOutput("To use Taborator right click in the repeater request tab and select \"Taborator->Insert Collaborator payload\". Use \"Taborator->Insert Collaborator placeholder\" to insert a placeholder that will be replaced by a Collaborator payload in every request. The Taborator placeholder also works in other Burp tools. You can also use the buttons in the Taborator tab to create a payload and poll now.");
                running = true;
                panel = new JPanel(new BorderLayout());
                JPanel topPanel = new JPanel();
                topPanel.setLayout(new GridBagLayout());
                JButton exportBtn = new JButton("Export");
                exportBtn.addActionListener(e -> {
                    Frame parentFrame = api.userInterface().swingUtils().suiteFrame();
                    JFileChooser fileChooser = new JFileChooser();
                    fileChooser.setDialogTitle("Please choose where to save interactions");
                    int userSelection = fileChooser.showSaveDialog(parentFrame);
                    if (userSelection == JFileChooser.APPROVE_OPTION) {
                        File fileToSave = fileChooser.getSelectedFile();
                        String filePath = fileToSave.getAbsolutePath();
                        saveSettings();
                        String jsonStr = settings.getInteractionHistory().toString();
                        try (FileWriter file = new FileWriter(filePath)) {
                            file.write(jsonStr);
                        } catch (IOException ex) {
                            api.logging().logToError("Failed to export interactions: " + ex.getMessage());
                        }
                    }
                });
                JLabel searchText = new JLabel("Search (IP,Host):");
                JTextField keywordSearch = new JTextField();
                keywordSearch.setPreferredSize(new Dimension(160, 30));
                JComboBox<String> filter = new JComboBox<>();
                filter.setPreferredSize(new Dimension(160, 30));
                filter.addItem("Show all types");
                filter.addItem("DNS");
                filter.addItem("HTTP");
                filter.addItem("SMTP");

                RowFilter<TableModel, Integer> rowFilter = new RowFilter<>() {
                    @Override
                    public boolean include(RowFilter.Entry<? extends TableModel,? extends Integer> row) {
                        String keyword = keywordSearch.getText();
                        Boolean hasFilter = filter.getSelectedIndex() > 0;
                        Boolean hasKeyword = !keyword.equals("");
                        if(!hasFilter && !hasKeyword) {
                            return true;
                        }
                        if(hasKeyword && hasFilter) {
                            return (row.getStringValue(3).contains(keyword) || row.getStringValue(4).contains(keyword)) && row.getValue(2).equals(filter.getSelectedItem().toString());
                        } else if(hasKeyword) {
                            return row.getStringValue(3).contains(keyword) || row.getStringValue(4).contains(keyword);
                        } else if(hasFilter) {
                            return row.getValue(2).equals(filter.getSelectedItem().toString());
                        } else {
                            return true;
                        }
                    }
                };
                filter.addActionListener(e -> sorter.setRowFilter(rowFilter));
                keywordSearch.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        sorter.setRowFilter(rowFilter);
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        sorter.setRowFilter(rowFilter);
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                        sorter.setRowFilter(rowFilter);
                    }
                });
                JButton createCollaboratorPayloadWithTaboratorCmd = new JButton("Taborator commands & copy");
                createCollaboratorPayloadWithTaboratorCmd.addActionListener(e -> {
                    createdCollaboratorPayload = true;
                    String payload = collaborator.generatePayload().toString() + "?TaboratorCmd=comment:Test;bgColour:0x000000;textColour:0xffffff";
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(payload), null);
                });
                JLabel generateMsg = new JLabel("Number to generate:");
                JButton pollButton = new JButton("Poll now");
                JTextField numberOfPayloads = new JTextField("1");
                numberOfPayloads.setPreferredSize(new Dimension(50, 30));
                JButton createCollaboratorPayload = new JButton("Create payload & copy");
                createCollaboratorPayload.addActionListener(e -> {
                    createdCollaboratorPayload = true;
                    int amount = 1;
                    try {
                        amount = Integer.parseInt(numberOfPayloads.getText());
                    } catch (NumberFormatException ex) {
                        amount = 1;
                    }
                    StringBuilder payloads = new StringBuilder();
                    payloads.append(collaborator.generatePayload().toString());
                    for (int i = 1; i < amount; i++) {
                        payloads.append("\n");
                        payloads.append(collaborator.generatePayload().toString());
                    }
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(payloads.toString()), null);
                });
                pollButton.addActionListener(e -> {
                    pollNow = true;
                    if (isSleeping) {
                        pollThread.interrupt();
                    }
                });
                topPanel.add(exportBtn, createConstraints(1, 2, 1, GridBagConstraints.NONE));
                topPanel.add(searchText, createConstraints(2, 2, 1, GridBagConstraints.NONE));
                topPanel.add(keywordSearch, createConstraints(3, 2, 1, GridBagConstraints.NONE));
                topPanel.add(filter, createConstraints(4, 2, 1, GridBagConstraints.NONE));
                topPanel.add(createCollaboratorPayloadWithTaboratorCmd, createConstraints(5, 2, 1, GridBagConstraints.NONE));
                topPanel.add(pollButton, createConstraints(6, 2, 1, GridBagConstraints.NONE));
                topPanel.add(generateMsg, createConstraints(7, 2, 1, GridBagConstraints.NONE));
                topPanel.add(numberOfPayloads, createConstraints(8,2,1, GridBagConstraints.NONE));
                topPanel.add(createCollaboratorPayload, createConstraints(9, 2, 1, GridBagConstraints.NONE));
                panel.add(topPanel, BorderLayout.NORTH);
                panel.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentShown(ComponentEvent e) {
                        pollNow = true;
                    }
                });
                interactionsTab = new JTabbedPane();
                JSplitPane collaboratorClientSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
                collaboratorClientSplit.setResizeWeight(.5d);
                final Class<?>[] classes = new Class<?>[]{Integer.class, Long.class, String.class, String.class, String.class, String.class};
                model = new DefaultTableModel() {
                    @Override
                    public boolean isCellEditable(int row, int column) {
                        return false;
                    }
                    @Override
                    public Class<?> getColumnClass(int columnIndex) {
                        if (columnIndex < classes.length)
                            return classes[columnIndex];
                        return super.getColumnClass(columnIndex);
                    }
                };
                collaboratorTable = new JTable(model);
                sorter = new TableRowSorter<>(model);
                collaboratorTable.setRowSorter(sorter);
                model.addColumn("#");
                model.addColumn("Time");
                model.addColumn("Type");
                model.addColumn("IP");
                model.addColumn("Hostname");
                model.addColumn("Comment");
                collaboratorTable.getColumnModel().getColumn(0).setPreferredWidth(50);
                collaboratorTable.getColumnModel().getColumn(0).setMaxWidth(50);
                collaboratorTable.getColumnModel().getColumn(2).setPreferredWidth(80);
                collaboratorTable.getColumnModel().getColumn(2).setMaxWidth(80);
                JPopupMenu popupMenu = new JPopupMenu();
                JMenuItem commentMenuItem = new JMenuItem("Add comment");
                commentMenuItem.addActionListener(e -> {
                    int rowNum = collaboratorTable.getSelectedRow();
                    if (rowNum > -1) {
                        int realRowNum = collaboratorTable.convertRowIndexToModel(rowNum);
                        String comment = JOptionPane.showInputDialog(api.userInterface().swingUtils().suiteFrame(), "Please enter a comment");
                        if (comment != null) {
                            collaboratorTable.getModel().setValueAt(comment, realRowNum, 5);
                            if (comment.isEmpty()) {
                                comments.remove(realRowNum);
                            } else {
                                comments.put(realRowNum, comment);
                            }
                        }
                    }
                });
                popupMenu.add(commentMenuItem);
                JMenu highlightMenu = new JMenu("Highlight");
                highlightMenu.add(generateMenuItem(collaboratorTable, null, "HTTP", null));
                highlightMenu.add(generateMenuItem(collaboratorTable, Color.decode("0xfa6364"), "HTTP", Color.white));
                highlightMenu.add(generateMenuItem(collaboratorTable, Color.decode("0xfac564"), "HTTP", Color.black));
                highlightMenu.add(generateMenuItem(collaboratorTable, Color.decode("0xfafa64"), "HTTP", Color.black));
                highlightMenu.add(generateMenuItem(collaboratorTable, Color.decode("0x63fa64"), "HTTP", Color.black));
                highlightMenu.add(generateMenuItem(collaboratorTable, Color.decode("0x63fafa"), "HTTP", Color.black));
                highlightMenu.add(generateMenuItem(collaboratorTable, Color.decode("0x6363fa"), "HTTP", Color.white));
                highlightMenu.add(generateMenuItem(collaboratorTable, Color.decode("0xfac5c5"), "HTTP", Color.black));
                highlightMenu.add(generateMenuItem(collaboratorTable, Color.decode("0xfa63fa"), "HTTP", Color.black));
                highlightMenu.add(generateMenuItem(collaboratorTable, Color.decode("0xb1b1b1"), "HTTP", Color.black));
                popupMenu.add(highlightMenu);
                JMenuItem markReadMenuItem = new JMenuItem("Mark all as read");
                markReadMenuItem.addActionListener(e -> {
                    int answer = JOptionPane.showConfirmDialog(api.userInterface().swingUtils().suiteFrame(), "This will mark all interactions as read, are you sure?");
                    if (answer == JOptionPane.YES_OPTION) {
                        TableModel tableModel = collaboratorTable.getModel();
                        readRows = new ArrayList<>();
                        for (int i = 0; i < tableModel.getRowCount() + 1; i++) {
                            readRows.add(i);
                        }
                        unread = 0;
                        updateTab(false);
                        collaboratorTable.repaint();
                    }
                });
                JMenuItem clearMenuItem = new JMenuItem("Clear interactions");
                clearMenuItem.addActionListener(e -> {
                    int answer = JOptionPane.showConfirmDialog(api.userInterface().swingUtils().suiteFrame(), "This will clear all interactions, are you sure?");
                    if (answer == JOptionPane.YES_OPTION) {
                        interactionHistory.clear();
                        readRows.clear();
                        unread = 0;
                        rowNumber = 0;
                        colours.clear();
                        textColours.clear();
                        comments.clear();
                        model.setRowCount(0);
                        interactionsTab.removeAll();
                        selectedRow = -1;
                        updateTab(false);
                    }
                    collaboratorTable.clearSelection();
                });
                JMenuItem clearOriginalReqResItem = new JMenuItem("Clear original requests/responses");
                clearOriginalReqResItem.addActionListener(e -> {
                    int answer = JOptionPane.showConfirmDialog(api.userInterface().swingUtils().suiteFrame(), "This will remove all req/res history from placeholder usage, are you sure?");
                    if (answer == JOptionPane.YES_OPTION) {
                        originalRequests.clear();
                        originalResponses.clear();
                    }
                    collaboratorTable.clearSelection();
                });
                popupMenu.add(clearOriginalReqResItem);
                popupMenu.add(clearMenuItem);
                popupMenu.add(markReadMenuItem);
                collaboratorTable.setComponentPopupMenu(popupMenu);

                JScrollPane collaboratorScroll = new JScrollPane(collaboratorTable);
                collaboratorTable.setFillsViewportHeight(true);
                collaboratorClientSplit.setTopComponent(collaboratorScroll);
                collaboratorClientSplit.setBottomComponent(new JPanel());
                panel.add(collaboratorClientSplit, BorderLayout.CENTER);
                api.userInterface().registerSuiteTab(extensionName, panel);
                collaborator = api.collaborator().createClient();
                DefaultTableCellRenderer tableCellRender = new DefaultTableCellRenderer()
                {
                    @Override
                    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
                    {
                        final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                        int modelRow = table.convertRowIndexToModel(row);
                        int id = (int) table.getModel().getValueAt(modelRow, 0);
                        putClientProperty("html.disable", Boolean.TRUE);
                        if(isSelected) {
                            if(!readRows.contains(id)) {
                                c.setFont(c.getFont().deriveFont(Font.PLAIN));
                                readRows.add(id);
                                unread--;
                            }
                            if(selectedRow != row && collaboratorTable.getSelectedRowCount() == 1) {
                                JPanel descriptionPanel = new JPanel(new BorderLayout());
                                HashMap<String, String> interaction = interactionHistory.get(id);
                                JTextArea description = new JTextArea();
                                description.setEditable(false);
                                description.setBorder(null);
                                interactionsTab.removeAll();
                                interactionsTab.addTab("Description", descriptionPanel);
                                if(interaction.get("type").equals("DNS")) {
                                    description.setText("The Collaborator server received a DNS lookup of type " + interaction.get("query_type") + " for the hostname " + interaction.get("hostname") + "\n\n" +
                                            "The lookup was received from IP address " + interaction.get("client_ip") + " at " + interaction.get("time_stamp"));

                                    try {
                                        RawEditor dnsQueryEditor = api.userInterface().createRawEditor();
                                        dnsQueryEditor.setContents(ByteArray.byteArray(Base64.getDecoder().decode(interaction.get("raw_query"))));
                                        interactionsTab.addTab("DNS query", dnsQueryEditor.uiComponent());
                                    } catch (IllegalArgumentException e) {
                                        api.logging().logToError("Failed to decode DNS query: " + e.getMessage());
                                    }

                                    if(originalRequests.containsKey(interaction.get("interaction_id"))) {
                                        HashMap<String, String> requestInfo = originalRequests.get(interaction.get("interaction_id"));

                                        if (requestInfo.get("request") != null) {
                                            HttpRequestEditor requestMessageEditor = api.userInterface().createHttpRequestEditor();
                                            requestMessageEditor.setRequest(HttpRequest.httpRequest(ByteArray.byteArray(requestInfo.get("request").getBytes())));
                                            interactionsTab.addTab("Original request", requestMessageEditor.uiComponent());
                                        }
                                        if (originalResponses.containsKey(interaction.get("interaction_id"))) {
                                            if (requestInfo.get("request") != null && originalResponses.get(interaction.get("interaction_id")) != null) {
                                                HttpResponseEditor responseMessageEditor = api.userInterface().createHttpResponseEditor();
                                                responseMessageEditor.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(originalResponses.get(interaction.get("interaction_id")).getBytes())));
                                                interactionsTab.addTab("Original response", responseMessageEditor.uiComponent());
                                            }
                                        }
                                    }
                                } else if(interaction.get("type").equals("SMTP")) {
                                    try {
                                        byte[] conversation = Base64.getDecoder().decode(interaction.get("conversation"));
                                        String conversationString = api.utilities().byteUtils().convertToString(conversation);
                                        String to = "";
                                        String from = "";
                                        String message = "";
                                        Matcher m = SMTP_RCPT_TO_PATTERN.matcher(conversationString);
                                        if(m.find()) {
                                            to = m.group(1).trim();
                                        }
                                        m = SMTP_MAIL_FROM_PATTERN.matcher(conversationString);
                                        if(m.find()) {
                                            from = m.group(1).trim();
                                        }
                                        m = SMTP_DATA_PATTERN.matcher(conversationString);
                                        if(m.find()) {
                                            message = m.group(1).trim();
                                        }
                                        description.setText(
                                                "The Collaborator server received a SMTP connection from IP address " + interaction.get("client_ip") + " at " + interaction.get("time_stamp") + ".\n\n" +
                                                        "The email details were:\n\n" +
                                                        "From: " + from + "\n\n" +
                                                        "To: " + to + "\n\n" +
                                                        "Message: \n" + message
                                        );

                                        RawEditor smtpConversationEditor = api.userInterface().createRawEditor();
                                        smtpConversationEditor.setContents(ByteArray.byteArray(conversation));
                                        interactionsTab.addTab("SMTP Conversation", smtpConversationEditor.uiComponent());
                                        interactionsTab.setSelectedIndex(1);
                                    } catch (IllegalArgumentException e) {
                                        description.setText("The Collaborator server received a SMTP connection from IP address " + interaction.get("client_ip") + " at " + interaction.get("time_stamp") + ".\n\nError decoding conversation data.");
                                        api.logging().logToError("Failed to decode SMTP conversation: " + e.getMessage());
                                    }

                                    if(originalRequests.containsKey(interaction.get("interaction_id"))) {
                                        HashMap<String, String> requestInfo = originalRequests.get(interaction.get("interaction_id"));

                                        if (requestInfo.get("request") != null) {
                                            HttpRequestEditor requestMessageEditor = api.userInterface().createHttpRequestEditor();
                                            requestMessageEditor.setRequest(HttpRequest.httpRequest(ByteArray.byteArray(requestInfo.get("request").getBytes())));
                                            interactionsTab.addTab("Original request", requestMessageEditor.uiComponent());
                                        }
                                        if (originalResponses.containsKey(interaction.get("interaction_id"))) {
                                            if (requestInfo.get("request") != null && originalResponses.get(interaction.get("interaction_id")) != null) {
                                                HttpResponseEditor responseMessageEditor = api.userInterface().createHttpResponseEditor();
                                                responseMessageEditor.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(originalResponses.get(interaction.get("interaction_id")).getBytes())));
                                                interactionsTab.addTab("Original response", responseMessageEditor.uiComponent());
                                            }
                                        }
                                    }
                                } else if(interaction.get("type").equals("HTTP")) {
                                    description.setText("The Collaborator server received an "+interaction.get("protocol")+" request.\n\nThe request was received from IP address "+interaction.get("client_ip")+" at "+interaction.get("time_stamp") + " for the hostname " + interaction.get("hostname"));

                                    if(originalRequests.containsKey(interaction.get("interaction_id"))) {
                                        HashMap<String, String> requestInfo = originalRequests.get(interaction.get("interaction_id"));

                                        if (requestInfo.get("request") != null) {
                                            HttpRequestEditor origRequestEditor = api.userInterface().createHttpRequestEditor();
                                            origRequestEditor.setRequest(HttpRequest.httpRequest(ByteArray.byteArray(requestInfo.get("request").getBytes())));
                                            interactionsTab.addTab("Original request", origRequestEditor.uiComponent());
                                        }
                                        if (originalResponses.containsKey(interaction.get("interaction_id"))) {
                                            if (requestInfo.get("request") != null && originalResponses.get(interaction.get("interaction_id")) != null) {
                                                HttpResponseEditor origResponseEditor = api.userInterface().createHttpResponseEditor();
                                                origResponseEditor.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(originalResponses.get(interaction.get("interaction_id")).getBytes())));
                                                interactionsTab.addTab("Original response", origResponseEditor.uiComponent());
                                            }
                                        }
                                    }

                                    try {
                                        byte[] collaboratorRequest = Base64.getDecoder().decode(interaction.get("request"));
                                        byte[] collaboratorResponse = Base64.getDecoder().decode(interaction.get("response"));

                                        HttpRequestEditor collabRequestEditor = api.userInterface().createHttpRequestEditor();
                                        collabRequestEditor.setRequest(HttpRequest.httpRequest(ByteArray.byteArray(collaboratorRequest)));
                                        interactionsTab.addTab("Request to Collaborator", collabRequestEditor.uiComponent());

                                        HttpResponseEditor collabResponseEditor = api.userInterface().createHttpResponseEditor();
                                        collabResponseEditor.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(collaboratorResponse)));
                                        interactionsTab.addTab("Response from Collaborator", collabResponseEditor.uiComponent());
                                        interactionsTab.setSelectedIndex(1);
                                    } catch (IllegalArgumentException e) {
                                        api.logging().logToError("Failed to decode HTTP request/response: " + e.getMessage());
                                    }
                                }
                                description.setBorder(BorderFactory.createCompoundBorder(description.getBorder(), BorderFactory.createEmptyBorder(10, 10, 10, 10)));
                                descriptionPanel.add(description);
                                collaboratorClientSplit.setBottomComponent(interactionsTab);
                                selectedRow = row;
                                updateTab(false);
                                setDividerLocation(collaboratorClientSplit, 0.5);
                            }
                        } else {
                            if(!readRows.contains(id)) {
                                c.setFont(c.getFont().deriveFont(Font.BOLD));
                            }
                        }
                        if(colours.containsKey(id) && isSelected) {
                            if(colours.get(id) == null) {
                                setBackground(colours.get(id));
                                colours.remove(id);
                                textColours.remove(id);
                            } else {
                                setBackground(colours.get(id).darker());
                            }
                            setForeground(textColours.get(id));
                            table.repaint();
                            table.validate();
                        } else if(colours.containsKey(id)) {
                            setBackground(colours.get(id));
                            setForeground(textColours.get(id));
                        } else if(isSelected) {
                            if(UIManager.getLookAndFeel().getID().equals("Darcula")) {
                                setBackground(Color.decode("0x0d293e"));
                                setForeground(Color.white);
                            } else {
                                setBackground(Color.decode("0xffc599"));
                                setForeground(Color.black);
                            }
                        } else {
                            setBackground(null);
                            setForeground(null);
                        }
                        return c;
                    }
                };
                collaboratorTable.setDefaultRenderer(Object.class, tableCellRender);
                collaboratorTable.setDefaultRenderer(Number.class, tableCellRender);
                Runnable collaboratorRunnable = () -> {
                        try {
                            api.logging().logToOutput("Taborator running...");
                            loadSettings();
                            for (Map.Entry<Integer, HashMap<String, String>> data : interactionHistory.entrySet()) {
                                int id = data.getKey();
                                HashMap<String, String> interaction = data.getValue();
                                insertInteraction(interaction, id);
                            }
                            if(unread > 0) {
                                updateTab(true);
                            }

                            while(running){
                                if(pollNow) {
                                    List<Interaction> interactions = collaborator.getAllInteractions();
                                    if(interactions.size() > 0) {
                                        insertInteractions(interactions);
                                    }
                                    pollNow = false;
                                }
                                try {
                                    isSleeping = true;
                                    Thread.sleep(POLL_EVERY_MS);
                                    isSleeping = false;
                                    pollCounter++;
                                    if(pollCounter > 5) {
                                        if(createdCollaboratorPayload) {
                                            pollNow = true;
                                        }
                                        pollCounter = 0;
                                    }
                                } catch (InterruptedException e) {
                                    if(shutdown) {
                                        api.logging().logToOutput("Taborator shutdown.");
                                        return;
                                    } else {
                                        continue;
                                    }

                                }
                            }
                            api.logging().logToOutput("Taborator shutdown.");
                        } catch (Exception e) {
                            api.logging().logToError("Taborator background thread error: " + e.getMessage());
                            StringWriter sw = new StringWriter();
                            e.printStackTrace(new PrintWriter(sw));
                            api.logging().logToError(sw.toString());
                        }
                };
                pollThread = new Thread(collaboratorRunnable);
                pollThread.start();
        });
    }
    private void insertInteraction(HashMap<String,String> interaction, int rowID) {
        // Parse TaboratorCmd before GUI update to extract colors/comments
        Color bgColour = null;
        Color txtColour = null;
        String commentFromCmd = null;

        if (interaction.get("type").equals("HTTP") && interaction.get("request") != null) {
            try {
                byte[] collaboratorRequest = Base64.getDecoder().decode(interaction.get("request"));
                if (api.utilities().byteUtils().indexOf(collaboratorRequest, api.utilities().byteUtils().convertFromString("TaboratorCmd="), true, 0, collaboratorRequest.length) > -1) {
                    var analyzedRequest = HttpRequest.httpRequest(ByteArray.byteArray(collaboratorRequest));
                    var params = analyzedRequest.parameters();
                    for (var param : params) {
                        if (param.name().equals("TaboratorCmd")) {
                            String[] commands = param.value().split(";");
                            for (int j = 0; j < commands.length; j++) {
                                String[] command = commands[j].split(":", 2);
                                if (command.length < 2) {
                                    continue;
                                }
                                if (command[0].equals("bgColour")) {
                                    try {
                                        bgColour = Color.decode(api.utilities().urlUtils().decode(command[1]));
                                    } catch (NumberFormatException e) {
                                        // Invalid color format, skip
                                    }
                                } else if (command[0].equals("textColour")) {
                                    try {
                                        txtColour = Color.decode(api.utilities().urlUtils().decode(command[1]));
                                    } catch (NumberFormatException e) {
                                        // Invalid color format, skip
                                    }
                                } else if (command[0].equals("comment")) {
                                    commentFromCmd = api.utilities().urlUtils().decode(command[1]);
                                }
                            }
                            break;
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                api.logging().logToError("Failed to decode HTTP request for TaboratorCmd: " + e.getMessage());
            }
        }

        // Store colors in thread-safe maps (ConcurrentHashMap)
        if (bgColour != null) {
            colours.put(rowID, bgColour);
        }
        if (txtColour != null) {
            textColours.put(rowID, txtColour);
        }

        // Perform GUI updates on EDT
        final String finalCommentFromCmd = commentFromCmd;
        SwingUtilities.invokeLater(() -> {
            model.addRow(new Object[]{rowID, interaction.get("time_stamp"), interaction.get("type"), interaction.get("client_ip"), interaction.get("hostname"), ""});
            if(comments.size() > 0) {
                int actualID = getRealRowID(rowID);
                if(actualID > -1 && comments.containsKey(actualID)) {
                    String comment = comments.get(actualID);
                    model.setValueAt(comment, actualID, 5);
                }
            }
            if (finalCommentFromCmd != null) {
                int actualID = getRealRowID(rowID);
                if(actualID > -1) {
                    model.setValueAt(finalCommentFromCmd, actualID, 5);
                }
            }
        });
    }
    private int getRealRowID(int rowID) {
        int rowCount = collaboratorTable.getRowCount();
        for (int i = 0; i < rowCount; i++) {
            int id = (int) collaboratorTable.getValueAt(i, 0);
            if(rowID == id) {
                return collaboratorTable.convertRowIndexToView(i);
            }
        }
        return -1;
    }
    private void loadSettings() {
        try {
            unread = settings.getUnread();
            rowNumber = settings.getRowNumber();
            interactionHistory = settings.getInteractionHistory();
            originalRequests = settings.getOriginalRequests();
            originalResponses = settings.getOriginalResponses();
            comments = settings.getComments();
            colours = settings.getColours();
            textColours = settings.getTextColours();
            readRows = settings.getReadRows();
        } catch(Throwable e) {
            api.logging().logToError("Error reading settings: " + e.getMessage());
        }
    }

    private void saveSettings() {
        try {
            settings.saveSettings(unread, rowNumber, interactionHistory, originalRequests,
                               originalResponses, readRows, comments, colours, textColours);
        } catch (Throwable e) {
            api.logging().logToError("Error saving settings: " + e.getMessage());
        }
    }
    private void insertInteractions(List<Interaction> interactions) {
        boolean hasInteractions = false;
        for(int i=0;i<interactions.size();i++) {
            Interaction interaction = interactions.get(i);
            HashMap<String, String> interactionHistoryItem = new HashMap<>();
            rowNumber++;
            int rowID = rowNumber;
            // Convert new Interaction to legacy format for existing code compatibility
            interactionHistoryItem.put("interaction_id", interaction.id().toString());
            interactionHistoryItem.put("type", interaction.type().toString());
            interactionHistoryItem.put("time_stamp", interaction.timeStamp().toString());
            interactionHistoryItem.put("client_ip", interaction.clientIp().getHostAddress());
            
            // Add specific details based on interaction type
            if (interaction.dnsDetails().isPresent()) {
                var dnsDetails = interaction.dnsDetails().get();
                interactionHistoryItem.put("raw_query", Base64.getEncoder().encodeToString(dnsDetails.query().getBytes()));
                interactionHistoryItem.put("query_type", dnsDetails.queryType().toString());
            }
            if (interaction.httpDetails().isPresent()) {
                var httpDetails = interaction.httpDetails().get();
                interactionHistoryItem.put("request", Base64.getEncoder().encodeToString(httpDetails.requestResponse().request().toByteArray().getBytes()));
                interactionHistoryItem.put("response", Base64.getEncoder().encodeToString(httpDetails.requestResponse().response().toByteArray().getBytes()));
                interactionHistoryItem.put("protocol", httpDetails.requestResponse().request().httpService().secure() ? "HTTPS" : "HTTP");
            }
            if (interaction.smtpDetails().isPresent()) {
                var smtpDetails = interaction.smtpDetails().get();
                interactionHistoryItem.put("conversation", Base64.getEncoder().encodeToString(smtpDetails.conversation().getBytes()));
            }
            
            interactionHistoryItem.put("hostname", getHostnameFromInteraction(interactionHistoryItem));
            insertInteraction(interactionHistoryItem, rowID);
            unread++;
            interactionHistory.put(rowID, interactionHistoryItem);
            hasInteractions = true;
        }
        updateTab(hasInteractions);
    }
    // These methods are no longer needed with Montoya API
    public Component getUiComponent() {
        return panel;
    }
    public String getTabCaption() {
        return unread > 0 ? extensionName + " ("+unread+")" : extensionName;
    }

    private void changeTabColour(JTabbedPane tabbedPane, final int tabIndex, boolean hasInteractions) {
        if(hasInteractions) {
            tabbedPane.setBackgroundAt(tabIndex, new Color(0xff6633));
        } else {
            tabbedPane.setBackgroundAt(tabIndex, defaultTabColour);
        }
    }
    private Color getDefaultTabColour() {
        if(running) {
            JTabbedPane tp = (JTabbedPane) BurpExtender.this.getUiComponent().getParent();
            int tIndex = getTabIndex();
            if (tIndex > -1) {
                return tp.getBackgroundAt(tIndex);
            }
            return new Color(0x000000);
        }
        return null;
    }
    private void updateTab(boolean hasInteractions) {
        if(running) {
            Runnable updateRunnable = () -> {
                JTabbedPane tp = (JTabbedPane) BurpExtender.this.getUiComponent().getParent();
                int tIndex = getTabIndex();
                if (tIndex > -1) {
                    tp.setTitleAt(tIndex, getTabCaption());
                    changeTabColour(tp, tIndex, hasInteractions);
                }
            };
            if (SwingUtilities.isEventDispatchThread()) {
                updateRunnable.run();
            } else {
                SwingUtilities.invokeLater(updateRunnable);
            }
        }
    }

    private int getTabIndex() {
        if(running) {
            JTabbedPane parent = (JTabbedPane) panel.getParent();
            for (int i = 0; i < parent.getTabCount(); ++i) {
                if (parent.getTitleAt(i).contains(extensionName)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private JMenuItem generateMenuItem(JTable collaboratorTable, Color colour, String text, Color textColour) {
        JMenuItem item = new JMenuItem(text);
        item.setBackground(colour);
        item.setForeground(textColour);
        item.setOpaque(true);
        item.addActionListener(e -> {
            int[] rows = collaboratorTable.getSelectedRows();
            for (int row : rows) {
                int realRow = collaboratorTable.convertRowIndexToModel(row);
                if (realRow > -1) {
                    int id = (int) collaboratorTable.getModel().getValueAt(realRow, 0);
                    colours.put(id, colour);
                    textColours.put(id, textColour);
                }
            }
        });
        return item;
    }

    public static JSplitPane setDividerLocation(final JSplitPane splitter, final double proportion) {
        if (splitter.isShowing()) {
            if ((splitter.getWidth() > 0) && (splitter.getHeight() > 0)) {
                splitter.setDividerLocation(proportion);
            } else {
                splitter.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentResized(ComponentEvent ce) {
                        splitter.removeComponentListener(this);
                        setDividerLocation(splitter, proportion);
                    }
                });
            }
        } else {
            splitter.addHierarchyListener(new HierarchyListener() {
                @Override
                public void hierarchyChanged(HierarchyEvent e) {
                    if (((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) && splitter.isShowing()) {
                        splitter.removeHierarchyListener(this);
                        setDividerLocation(splitter, proportion);
                    }
                }
            });
        }
        return splitter;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
        byte[] request = requestToBeSent.toByteArray().getBytes();
        if (api.utilities().byteUtils().indexOf(request, api.utilities().byteUtils().convertFromString(COLLABORATOR_PLACEHOLDER), true, 0, request.length) > -1) {
            String requestStr = api.utilities().byteUtils().convertToString(request);
            Matcher m = COLLABORATOR_PLACEHOLDER_PATTERN.matcher(requestStr);
            ArrayList<String> collaboratorPayloads = new ArrayList<>();
            while (m.find()) {
                String collaboratorPayloadID = collaborator.generatePayload().id().toString();
                collaboratorPayloads.add(collaboratorPayloadID);
                String replacement = collaboratorPayloadID + "." + collaborator.server().address();
                requestStr = requestStr.replaceFirst(COLLABORATOR_PLACEHOLDER.replace("$", "\\$"), replacement);
                pollNow = true;
                createdCollaboratorPayload = true;
            }
            request = api.utilities().byteUtils().convertFromString(requestStr);
            request = fixContentLength(request);

            for (int i = 0; i < collaboratorPayloads.size(); i++) {
                HashMap<String, String> originalRequestsInfo = new HashMap<>();
                originalRequestsInfo.put("request", api.utilities().byteUtils().convertToString(request));
                originalRequestsInfo.put("host", requestToBeSent.httpService().host());
                originalRequestsInfo.put("port", Integer.toString(requestToBeSent.httpService().port()));
                originalRequestsInfo.put("protocol", requestToBeSent.httpService().secure() ? "https" : "http");
                originalRequests.put(collaboratorPayloads.get(i), originalRequestsInfo);
            }
            
            // Create new request while preserving the original HTTP service
            var httpService = requestToBeSent.httpService();
            var newRequest = HttpRequest.httpRequest(httpService, ByteArray.byteArray(request));
            return RequestToBeSentAction.continueWith(newRequest);
        }
        return RequestToBeSentAction.continueWith(requestToBeSent);
    }
    
    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        byte[] response = responseReceived.toByteArray().getBytes();
        byte[] request = responseReceived.initiatingRequest().toByteArray().getBytes();
        for (Map.Entry<String, HashMap<String, String>> entry : originalRequests.entrySet()) {
            String payload = entry.getKey();
            if(!originalResponses.containsKey(payload) && api.utilities().byteUtils().indexOf(request, api.utilities().byteUtils().convertFromString(payload), true, 0, request.length) > -1) {
                originalResponses.put(payload, api.utilities().byteUtils().convertToString(response));
            }
        }
        return ResponseReceivedAction.continueWith(responseReceived);
    }

    private GridBagConstraints createConstraints(int x, int y, int gridWidth, int fill) {
        GridBagConstraints c = new GridBagConstraints();
        c.fill = fill;
        c.weightx = 0;
        c.weighty = 0;
        c.gridx = x;
        c.gridy = y;
        c.ipadx = 0;
        c.ipady = 0;
        c.gridwidth = gridWidth;
        c.insets = new Insets(5,5,5,5);
        return c;
    }
    public byte[] fixContentLength(byte[] request) {
        var analyzedRequest = HttpRequest.httpRequest(ByteArray.byteArray(request));
        if (countMatches(request, api.utilities().byteUtils().convertFromString("Content-Length: ")) > 0) {
            int start = analyzedRequest.bodyOffset();
            int contentLength = request.length - start;
            return setHeader(request, "Content-Length", Integer.toString(contentLength));
        }
        else {
            return request;
        }
    }

    public int[] getHeaderOffsets(byte[] request, String header) {
        int i = 0;
        int end = request.length;
        while (i < end) {
            int line_start = i;
            while (i < end && request[i++] != ' ') {
            }
            byte[] header_name = Arrays.copyOfRange(request, line_start, i - 2);
            int headerValueStart = i;
            while (i < end && request[i++] != '\n') {
            }
            if (i == end) {
                break;
            }

            String header_str = new String(header_name);

            if (header.equals(header_str)) {
                int[] offsets = {line_start, headerValueStart, i - 2};
                return offsets;
            }

            if (i + 2 < end && request[i] == '\r' && request[i + 1] == '\n') {
                break;
            }
        }
        return null;
    }

    public  byte[] setHeader(byte[] request, String header, String value) {
        int[] offsets = getHeaderOffsets(request, header);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            outputStream.write( Arrays.copyOfRange(request, 0, offsets[1]));
            outputStream.write(value.getBytes());
            outputStream.write(Arrays.copyOfRange(request, offsets[2], request.length));
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Request creation unexpectedly failed");
        } catch (NullPointerException e) {
            throw new RuntimeException("Can't find the header");
        }
    }

    int countMatches(byte[] response, byte[] match) {
        int matches = 0;
        if (match.length < 4) {
            return matches;
        }

        int start = 0;
        while (start < response.length) {
            start = api.utilities().byteUtils().indexOf(response, match, true, start, response.length);
            if (start == -1)
                break;
            matches += 1;
            start += match.length;
        }

        return matches;
    }

    private String getHostnameFromInteraction(HashMap<String, String> interaction) {
        String fallback = interaction.get("interaction_id") + "." + collaborator.server().address();
        try {
            switch(interaction.get("type")) {
                case "DNS":
                    return getHostnameFromDnsRequest(Base64.getDecoder().decode(interaction.get("raw_query")), fallback);
                case "HTTP":
                    return getHostnameFromHttpRequest(api.utilities().byteUtils().convertToString(Base64.getDecoder().decode(interaction.get("request"))), fallback);
                case "SMTP":
                    return getHostnameFromSmtpConversation(api.utilities().byteUtils().convertToString(Base64.getDecoder().decode(interaction.get("conversation"))), fallback);
                default:
                    return fallback;
            }
        } catch (IllegalArgumentException e) {
            api.logging().logToError("Failed to decode Base64 data for interaction: " + e.getMessage());
            return fallback;
        }
    }

    private static String getHostnameFromDnsRequest(byte[] rawQuery, String fallback) {
        StringBuilder hostname = new StringBuilder();
        HashSet<Integer> seenPtrs = new HashSet<>();

        ByteBuffer bb = ByteBuffer.wrap(rawQuery);

        // Seek to QDCOUNT
        bb.position(4);

        // If there is a number of questions other than 1 in the query, something has gone wrong
        short num_questions = bb.getShort();
        if (num_questions != 1) {
            return fallback;
        }

        // Seek to Question section
        bb.position(12);

        // Read hostname
        try {
            while (true) {
                int token_prefix = bb.get();

                if (token_prefix == 0) {
                    // Reached the end of the hostname
                    break;
                }

                if ((token_prefix & 0xc0) == 0) {
                    // It's a length value. Grab the token and follow it up with a dot.
                    for (int i = 0; i < token_prefix; i++) {
                        hostname.append((char) bb.get());
                    }
                    hostname.append(".");
                } else {
                    // It's a special value
                    if ((token_prefix & 0xc0) != 0xc0) {
                        // It's an illegal (reserved) value
                        return fallback;
                    }
                    // It's a pointer value. See RFC1035 section 4.1.4
                    // This isn't necessarily a correct implementation. The Burp Collaborator server doesn't seem to
                    // support pointers anyway and we don't really expect to see pointers in DNS queries (?)

                    // Rewind pos and get the ptr as a short with the high two bits masked off
                    bb.position(bb.position() - 1);
                    int ptr = bb.getShort() & (0xff - 0xc0);

                    // Check for loops
                    if (seenPtrs.contains(ptr)) {
                        return fallback;
                    }
                    seenPtrs.add(ptr);

                    // Move to where the pointer points
                    bb.position(ptr);
                }
            }
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            // OOB error in the ByteBuffer.get() or .position()
            return fallback;
        }

        if (hostname.length() == 0) {
            return hostname.toString();
        } else {
            // Remove the trailing "."
            return hostname.substring(0, hostname.length() - 1);
        }
    }

    private static String getHostnameFromHttpRequest(String request, String fallback) {
        String[] lines = request.split("\r\n");
        for (String line : lines) {
            if (line.isEmpty()) {
                break;
            } else if (line.toLowerCase(Locale.ROOT).startsWith("host: ")) {
                return line.split(" ", 2)[1];
            }
        }
        return fallback;
    }

    private static String getHostnameFromSmtpConversation(String conversation, String fallback) {
        String[] lines = conversation.split("\r\n");
        for (String line : lines) {
            if (line.toLowerCase(Locale.ROOT).startsWith("rcpt to:")) {
                String recipient = line.split(":", 2)[1].trim();
                Matcher m = BRACKETED_ADDRESS_PATTERN.matcher(recipient);
                if (m.find()) {
                    // Parsing email addresses is hard but hopefully we've just found a bracketed email address
                    // e.g. <peter@example.com>
                    recipient = m.group(1).trim();
                }
                int pos = recipient.lastIndexOf("@");
                return recipient.substring(pos + 1);
            }
        }
        return fallback;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<Component> menu = new ArrayList<>();
        
        // Show context menu for message editors and request/response viewers
        if (event.messageEditorRequestResponse().isPresent() || !event.selectedRequestResponses().isEmpty()) {
            JMenu submenu = new JMenu(extensionName);
            
            JMenuItem createPayload = new JMenuItem("Insert Collaborator payload");
            createPayload.addActionListener(e -> {
                String payload = collaborator.generatePayload().toString();
                insertTextIntoEditor(event, payload);
                pollNow = true;
                createdCollaboratorPayload = true;
            });
            
            JMenuItem createPlaceholder = new JMenuItem("Insert Collaborator placeholder");
            createPlaceholder.addActionListener(e -> {
                insertTextIntoEditor(event, COLLABORATOR_PLACEHOLDER);
                pollNow = true;
                createdCollaboratorPayload = true;
            });
            
            submenu.add(createPayload);
            submenu.add(createPlaceholder);
            menu.add(submenu);
        }
        
        return menu;
    }
    
    private JTextComponent findEditorTextComponent(Object event) {
        // Get the source component from the input event that triggered the context menu / hotkey.
        // This is more reliable than KeyboardFocusManager because focus shifts to the popup menu.
        Component source = null;
        if (event instanceof ContextMenuEvent) {
            source = ((ContextMenuEvent) event).inputEvent().getComponent();
        } else if (event instanceof HotKeyEvent) {
            source = ((HotKeyEvent) event).inputEvent().getComponent();
        }

        if (source instanceof JTextComponent) {
            return (JTextComponent) source;
        }

        // Walk down the component tree to find a JTextComponent child
        if (source instanceof Container) {
            return findTextComponentIn((Container) source);
        }
        return null;
    }

    private JTextComponent findTextComponentIn(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JTextComponent) {
                return (JTextComponent) child;
            }
            if (child instanceof Container) {
                JTextComponent found = findTextComponentIn((Container) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void insertTextIntoEditor(Object event, String textToInsert) {
        // Try to insert directly into the Swing text component via the input event source.
        // This avoids setRequest() which replaces the entire content and resets scroll position.
        JTextComponent textComponent = findEditorTextComponent(event);
        if (textComponent != null) {
            // replaceSelection handles both cases:
            // - If text is selected, it replaces the selection
            // - If no text is selected, it inserts at the caret position
            textComponent.replaceSelection(textToInsert);
            return;
        }

        // Fallback: use the Montoya API setRequest() approach
        if (event instanceof ContextMenuEvent && ((ContextMenuEvent) event).messageEditorRequestResponse().isPresent()) {
            var messageEditor = ((ContextMenuEvent) event).messageEditorRequestResponse().get();
            byte[] modifiedRequest = buildModifiedRequest(messageEditor, textToInsert);
            if (modifiedRequest == null) return;
            messageEditor.setRequest(HttpRequest.httpRequest(ByteArray.byteArray(modifiedRequest)));

        } else if (event instanceof HotKeyEvent && ((HotKeyEvent) event).messageEditorRequestResponse().isPresent()) {
            var messageEditor = ((HotKeyEvent) event).messageEditorRequestResponse().get();
            byte[] modifiedRequest = buildModifiedRequest(messageEditor, textToInsert);
            if (modifiedRequest == null) return;
            messageEditor.setRequest(HttpRequest.httpRequest(ByteArray.byteArray(modifiedRequest)));

        } else {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(textToInsert), null);
        }
    }

    private byte[] buildModifiedRequest(
            burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse messageEditor,
            String textToInsert) {
        var currentRequestBytes = messageEditor.requestResponse().request().toByteArray().getBytes();
        var selection = messageEditor.selectionOffsets();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            if (selection.isPresent() && selection.get().startIndexInclusive() != selection.get().endIndexExclusive()) {
                int startOffset = selection.get().startIndexInclusive();
                int endOffset = selection.get().endIndexExclusive();
                outputStream.write(Arrays.copyOfRange(currentRequestBytes, 0, startOffset));
                outputStream.write(textToInsert.getBytes());
                outputStream.write(Arrays.copyOfRange(currentRequestBytes, endOffset, currentRequestBytes.length));
            } else {
                int caretPosition = messageEditor.caretPosition();
                if (caretPosition < 0) caretPosition = 0;
                if (caretPosition > currentRequestBytes.length) caretPosition = currentRequestBytes.length;
                outputStream.write(Arrays.copyOfRange(currentRequestBytes, 0, caretPosition));
                outputStream.write(textToInsert.getBytes());
                outputStream.write(Arrays.copyOfRange(currentRequestBytes, caretPosition, currentRequestBytes.length));
            }
            return outputStream.toByteArray();
        } catch (Exception ex) {
            api.logging().logToError("Error inserting text: " + ex.getMessage());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(textToInsert), null);
            return null;
        }
    }

    private void insertCollaboratorPayload(HotKeyEvent event) {
        // For now, simply copy to clipboard as fallback
        CollaboratorPayload collaboratorPayload = collaborator.generatePayload();
        String insertText = collaboratorPayload.toString();

        insertTextIntoEditor(event, insertText);
        pollNow = true;
        createdCollaboratorPayload = true;
    }

    private void insertPlaceholder(HotKeyEvent event) {
        // For now, simply copy to clipboard as fallback
        String insertText = COLLABORATOR_PLACEHOLDER;
        insertTextIntoEditor(event, insertText);
    }

    public void extensionUnloaded() {
        shutdown = true;
        running = false;
        api.logging().logToOutput(extensionName + " unloaded");
        if (pollThread != null) {
            pollThread.interrupt();
        }
        saveSettings();
    }


}