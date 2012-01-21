package ca.site3.ssf.gamemodel;

/**
 * Abstract class for representing a general state of the Super Street Fire game.
 * @author Callum
 * @author Greg
 */
public abstract class GameState {

	/**
	 * The enumeration of the various game state types, useful for events and casting.
	 */
	public enum GameStateType { IDLE_STATE, ROUND_BEGINNING_STATE, ROUND_IN_PLAY_STATE,
		ROUND_ENDED_STATE, SETTLE_TIE_STATE, MATCH_OVER_STATE, PAUSED_STATE }
	
	protected GameModel gameModel = null;
	
	/**
	 * Constructor for GameState.
	 * @param gameModel The already created/established game model, used by the states.
	 */
	public GameState(GameModel gameModel) {
		this.gameModel = gameModel;
		assert(this.gameModel != null);
	}

	// Event methods that must be implemented by child classes
	public abstract void tick(double dT);
	public abstract void killToIdle();
	public abstract void initiateNextMatchRound();
	public abstract void togglePause();
	public abstract GameState.GameStateType getStateType();

}
