import javax.swing.*;
import java.awt.*;

public class Splash extends JFrame {
    private static final long serialVersionUID = 1L;
    public Splash() {
        setTitle("MagaDrop"); setSize(400, 220); setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); setLayout(new GridBagLayout());
        JPanel painel = new JPanel(new GridLayout(0, 1, 0, 12));
        JLabel titulo = new JLabel("MagaDrop", SwingConstants.CENTER);
        titulo.setFont(new Font("Segoe UI", Font.BOLD, 24));
        painel.add(titulo); painel.add(new JLabel("Preparando o compartilhamento...", SwingConstants.CENTER)); add(painel);
    }
}
