package com.jmeter.autocorrelator;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.apache.jmeter.config.gui.AbstractConfigGui;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;

public class AutoCorrelatorGui extends AbstractConfigGui {

    private DefaultTableModel tableModel;
    private JTable valueTable;
    private JLabel statusBar;
    
    // We store the actual samplers so we can modify them later
    private List<MatchRecord> foundMatches = new ArrayList<>();

    // Boundaries
    private final String LEFT_BOUNDARY = "EntityId%22%3A%22";
    private final String RIGHT_BOUNDARY = "%22%2C%22IsDirty";

    public AutoCorrelatorGui() {
        super();
        init();
    }

    private void init() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        VerticalPanel mainPanel = new VerticalPanel();

        // 1. Search Button
        JButton scanBtn = new JButton("Scan Test Plan for UUIDs");
        scanBtn.setBackground(new Color(51, 153, 255));
        scanBtn.setForeground(Color.WHITE);
        mainPanel.add(scanBtn);

        // 2. Table
        tableModel = new DefaultTableModel(new Object[]{"Select", "Request Name", "UUID Found"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }
        };
        valueTable = new JTable(tableModel);
        valueTable.getColumnModel().getColumn(0).setMaxWidth(60);
        mainPanel.add(new JScrollPane(valueTable));

        // 3. Action Buttons
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton selectAllBtn = new JButton("Select All");
        JButton deselectAllBtn = new JButton("Deselect All");
        JButton correlateBtn = new JButton("Correlate Selected");
        correlateBtn.setBackground(new Color(0, 153, 76));
        correlateBtn.setForeground(Color.WHITE);

        actionPanel.add(selectAllBtn);
        actionPanel.add(deselectAllBtn);
        actionPanel.add(correlateBtn);
        mainPanel.add(actionPanel);

        add(mainPanel, BorderLayout.CENTER);

        statusBar = new JLabel("Status: Ready");
        add(statusBar, BorderLayout.SOUTH);

        // Events
        scanBtn.addActionListener(e -> scanEntireTestPlan());
        selectAllBtn.addActionListener(e -> setSelectionAll(true));
        deselectAllBtn.addActionListener(e -> setSelectionAll(false));
        correlateBtn.addActionListener(e -> applyCorrelation());
    }

    // --- LOGIC: Scan the entire JMeter Script ---
    private void scanEntireTestPlan() {
        tableModel.setRowCount(0);
        foundMatches.clear();
        
        try {
            JMeterTreeModel treeModel = GuiPackage.getInstance().getTreeModel();
            JMeterTreeNode root = (JMeterTreeNode) treeModel.getRoot();
            traverseTreeForUuids(root);
            
            statusBar.setText("Status: Found " + foundMatches.size() + " UUIDs in the script.");
        } catch (Exception ex) {
            statusBar.setText("Error: " + ex.getMessage());
        }
    }

    // Recursive function to check every element in the script
    private void traverseTreeForUuids(JMeterTreeNode node) {
        TestElement element = node.getTestElement();

        if (element instanceof HTTPSamplerBase) {
            HTTPSamplerBase httpSampler = (HTTPSamplerBase) element;
            if (httpSampler.getArguments().getArgumentCount() > 0) {
                String body = httpSampler.getArguments().getArgument(0).getValue();
                
                Pattern pattern = Pattern.compile(LEFT_BOUNDARY + "(.*?)" + RIGHT_BOUNDARY);
                Matcher matcher = pattern.matcher(body);

                while (matcher.find()) {
                    String foundUuid = matcher.group(1);
                    // Save to our list
                    foundMatches.add(new MatchRecord(httpSampler, foundUuid));
                    // Add to GUI table
                    tableModel.addRow(new Object[]{true, httpSampler.getName(), foundUuid});
                }
            }
        }

        // Search child nodes (like requests inside Transaction Controllers)
        for (int i = 0; i < node.getChildCount(); i++) {
            traverseTreeForUuids((JMeterTreeNode) node.getChildAt(i));
        }
    }

    // --- LOGIC: Apply the Correlation ---
    private void applyCorrelation() {
        int replacedCount = 0;
        
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            boolean isSelected = (Boolean) tableModel.getValueAt(i, 0);
            
            if (isSelected) {
                MatchRecord record = foundMatches.get(i);
                HTTPSamplerBase sampler = record.sampler;
                String body = sampler.getArguments().getArgument(0).getValue();
                
                // Replace the specific hardcoded UUID with JMeter's dynamic UUID function
                String targetToReplace = LEFT_BOUNDARY + record.uuid + RIGHT_BOUNDARY;
                String replacement = LEFT_BOUNDARY + "${__UUID()}" + RIGHT_BOUNDARY;
                
                String newBody = body.replace(targetToReplace, replacement);
                sampler.getArguments().getArgument(0).setValue(newBody);
                replacedCount++;
            }
        }
        
        // Refresh the JMeter GUI so the user sees the changes immediately
        GuiPackage.getInstance().refreshCurrentGui();
        statusBar.setText("Status: Successfully correlated " + replacedCount + " requests.");
        JOptionPane.showMessageDialog(this, "Correlated " + replacedCount + " requests!\nHardcoded values replaced with ${__UUID()}");
    }

    private void setSelectionAll(boolean selected) {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            tableModel.setValueAt(selected, i, 0);
        }
    }

    @Override public String getStaticLabel() { return "UUID Auto-Correlator Tool"; }
    @Override public String getLabelResource() { return null; }
    @Override public TestElement createTestElement() {
        AutoCorrelatorElement el = new AutoCorrelatorElement();
        configureTestElement(el);
        return el;
    }
    @Override public void modifyTestElement(TestElement element) { configureTestElement(element); }

    // Helper class to store match data
    private class MatchRecord {
        HTTPSamplerBase sampler;
        String uuid;
        MatchRecord(HTTPSamplerBase sampler, String uuid) {
            this.sampler = sampler;
            this.uuid = uuid;
        }
    }
}