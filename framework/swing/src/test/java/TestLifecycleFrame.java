import androidx.jvm.swing.lifecycle.core.intent.WindowLifecycleAdapter;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;

class TestLifecycleFrame extends JFrame implements LifecycleOwner {
    private static final Logger log = LoggerFactory.getLogger(TestLifecycleFrame.class);

    LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    @Override
    public @NotNull Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    public TestLifecycleFrame() {
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        lifecycleRegistry.setCurrentState(Lifecycle.State.INITIALIZED);
        //添加到窗口状态监听后将窗口状态转换成生命周期状态
        WindowAdapter adapter = new WindowLifecycleAdapter(new Function1<Lifecycle.State, Unit>() {
            @Override
            public Unit invoke(Lifecycle.State state) {
                //将窗口状态转换而来的生命周期状态设置到lifecycleRegistry
                lifecycleRegistry.setCurrentState(state);
                return null;
            }
        });
        //添加到窗口状态监听
        this.addWindowListener(adapter);
        this.addWindowFocusListener(adapter);
        //打印生命周期变化
        lifecycleRegistry.addObserver(new LifecycleEventObserver() {
            @Override
            public void onStateChanged(@NotNull LifecycleOwner lifecycleOwner, @NotNull Lifecycle.Event event) {
                log.info(event.toString());
            }
        });

        //组件初始化,在这里没有实际作用, 只是让窗口看起来不太空.
        initComponents();
    }

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        scrollPane1 = new JScrollPane();
        tv_bookInfo = new JTextArea();
        contentView = new JPanel();
        label1 = new JLabel();
        hSpacer1 = new JPanel(null);
        button1 = new JButton();

        //======== this ========
        setTitle("\u4e3b\u7a97\u53e3");
        var contentPane = getContentPane();
        contentPane.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), 0, 0));

        //======== scrollPane1 ========
        {
            scrollPane1.setViewportView(tv_bookInfo);
        }
        contentPane.add(scrollPane1, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null));

        //======== contentView ========
        {
            contentView.setLayout(new BoxLayout(contentView, BoxLayout.X_AXIS));

            //---- label1 ----
            label1.setText("\u4e66\u7c4d\u7a97\u53e3");
            contentView.add(label1);

            //---- hSpacer1 ----
            hSpacer1.setMinimumSize(new Dimension(16, 12));
            contentView.add(hSpacer1);

            //---- button1 ----
            button1.setText("\u6253\u5f00");
            contentView.add(button1);
        }
        contentPane.add(contentView, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null));
        setSize(469, 250);
        setLocationRelativeTo(getOwner());
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JScrollPane scrollPane1;
    private JTextArea tv_bookInfo;
    private JPanel contentView;
    private JLabel label1;
    private JPanel hSpacer1;
    private JButton button1;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on

}
