package forms;

import java.awt.*;
import javax.swing.border.*;

import androidx.compose.desktop.runtime.core.intent.ComponentResultCallback;
import androidx.compose.desktop.runtime.core.intent.LaunchMode;
import androidx.compose.desktop.runtime.savestate.Tokens;
import androidx.core.bundle.Bundle;
import androidx.jvm.swing.lifecycle.core.intent.LaunchJFrameIntent;
import androidx.jvm.swing.lifecycle.jFrame.ComponentJFrame;
import androidx.jvm.swing.lifecycle.jFrame.JFrameManager;
import androidx.savedstate.SavedState;
import com.intellij.uiDesigner.core.*;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import static androidx.jvm.system.utils.JDKInfoKt.jdkInfo;

public class MainScreen extends ComponentJFrame {
    private static final Logger logger = LoggerFactory.getLogger(MainScreen.class);

    public MainScreen() {
        setupUI();
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }

    @Override
    public void onCreate(@Nullable SavedState savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    private void setupUI() {
        initComponents();
        //添加html标签以自动换行
        jdkinfo.setText("<html>" + jdkInfo() + "</html>");
        button1.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                LaunchJFrameIntent intent = new LaunchJFrameIntent(this, BookEditorExample.class, LaunchMode.STANDARD);
                intent.setTokenForJava(Tokens.of("BookEditorExample"));
                JFrameManager.openJFrameForResult(intent, new ComponentResultCallback() {
                    @Override
                    public void invoke(int resultCode, @Nullable Bundle data) {
                        logger.info("LaunchJFrameIntent resultCode: {}, data: {}", resultCode, data);
                        tv_bookInfo.setText(data.toString());
                    }
                });
            }
        });
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        label2 = new JLabel();
        jdkinfo = new JLabel();
        label3 = new JLabel();
        scrollPane1 = new JScrollPane();
        tv_bookInfo = new JTextArea();
        bottomView = new JPanel();
        label1 = new JLabel();
        hSpacer1 = new JPanel(null);
        button1 = new JButton();

        //======== this ========
        setTitle("\u4e3b\u7a97\u53e3");
        var contentPane = getContentPane();
        contentPane.setLayout(new GridLayoutManager(3, 2, new Insets(8, 8, 8, 8), 8, 8));

        //---- label2 ----
        label2.setText("jdk\u4fe1\u606f");
        contentPane.add(label2, new GridConstraints(0, 0, 1, 1,
            GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
            GridConstraints.SIZEPOLICY_CAN_SHRINK,
            GridConstraints.SIZEPOLICY_CAN_SHRINK,
            null, null, null));

        //---- jdkinfo ----
        jdkinfo.setText("text");
        contentPane.add(jdkinfo, new GridConstraints(0, 1, 1, 1,
            GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
            GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
            GridConstraints.SIZEPOLICY_CAN_SHRINK,
            null, null, null));

        //---- label3 ----
        label3.setText("\u4e66\u7c4d\u4fe1\u606f");
        contentPane.add(label3, new GridConstraints(1, 0, 1, 1,
            GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
            GridConstraints.SIZEPOLICY_CAN_SHRINK,
            GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
            null, null, null));

        //======== scrollPane1 ========
        {

            //---- tv_bookInfo ----
            tv_bookInfo.setEditable(false);
            tv_bookInfo.setLineWrap(true);
            tv_bookInfo.setWrapStyleWord(true);
            scrollPane1.setViewportView(tv_bookInfo);
        }
        contentPane.add(scrollPane1, new GridConstraints(1, 1, 1, 1,
            GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
            GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
            GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
            null, null, null));

        //======== bottomView ========
        {
            bottomView.setLayout(new BoxLayout(bottomView, BoxLayout.X_AXIS));

            //---- label1 ----
            label1.setText("\u4e66\u7c4d\u7a97\u53e3");
            bottomView.add(label1);

            //---- hSpacer1 ----
            hSpacer1.setMinimumSize(new Dimension(16, 12));
            bottomView.add(hSpacer1);

            //---- button1 ----
            button1.setText("\u6253\u5f00");
            bottomView.add(button1);
        }
        contentPane.add(bottomView, new GridConstraints(2, 0, 1, 2,
            GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE,
            GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
            GridConstraints.SIZEPOLICY_CAN_SHRINK,
            null, null, null));
        setSize(612, 356);
        setLocationRelativeTo(getOwner());
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JLabel label2;
    private JLabel jdkinfo;
    private JLabel label3;
    private JScrollPane scrollPane1;
    private JTextArea tv_bookInfo;
    private JPanel bottomView;
    private JLabel label1;
    private JPanel hSpacer1;
    private JButton button1;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
