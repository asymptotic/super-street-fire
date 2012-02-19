package ca.site3.ssf.devgui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;

import ca.site3.ssf.gamemodel.GameState;

class GameInfoPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private JLabel currStateLabel;
	private JLabel currRoundTimeLabel;
	
	private PlayerInfoPanel player1Panel;
	private PlayerInfoPanel player2Panel;
	
	GameInfoPanel() {
		super();
		
		TitledBorder border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.black), "Game Information");
		border.setTitleColor(Color.black);
		this.setBorder(border);
		
		
		// GAME STATE INFO PANEL *********************************************
		JPanel stateInfoPanel = new JPanel();
		
        GridBagLayout layout = new GridBagLayout();
        stateInfoPanel.setLayout(layout);

		FormLayoutHelper formLayoutHelper = new FormLayoutHelper();		
		
		JLabel currStateLabel = new JLabel("Game State:");
		currStateLabel.setForeground(Color.black);
		formLayoutHelper.addLabel(currStateLabel, stateInfoPanel);
		this.currStateLabel = new JLabel("N/A");
		this.currStateLabel.setForeground(Color.black);
		formLayoutHelper.addLastField(this.currStateLabel, stateInfoPanel);
		
		JLabel roundTimeLabel = new JLabel("Round Time:");
		roundTimeLabel.setForeground(Color.black);
		formLayoutHelper.addLabel(roundTimeLabel, stateInfoPanel);
		
		this.currRoundTimeLabel = new JLabel("N/A");
		this.currRoundTimeLabel.setForeground(Color.black);
		Font tempFont = this.currRoundTimeLabel.getFont();
		this.currRoundTimeLabel.setFont(new Font(tempFont.getFontName(), tempFont.getStyle(), 32));
		formLayoutHelper.addLastField(this.currRoundTimeLabel, stateInfoPanel);
		
		// ********************************************************************
		
		this.player1Panel = new PlayerInfoPanel(1);
		this.player2Panel = new PlayerInfoPanel(2);

		this.setLayout(new GridLayout(0,3));
		
		this.add(stateInfoPanel);
		this.add(player1Panel);
		this.add(player2Panel);
	}

	void setRoundTimer(double time) {
		if (time < 0.0) {
			this.currRoundTimeLabel.setText("N/A");
		}
		else {
			this.currRoundTimeLabel.setText("" + (int)time);
		}
	}
	double getRoundTime() {
		return Double.parseDouble(this.currRoundTimeLabel.getText());
	}
	
	void setGameState(GameState.GameStateType state) {
		this.currStateLabel.setText(state.toString());
	}

	PlayerInfoPanel getPlayer1Panel() {
		return this.player1Panel;
	}
	PlayerInfoPanel getPlayer2Panel() {
		return this.player2Panel;
	}
	PlayerInfoPanel getPlayerPanel(int playerNum) {
		if (playerNum == 1) {
			return this.getPlayer1Panel();
		}
		else {
			return this.getPlayer2Panel();
		}
	}
	
}
