import java.awt.image.BufferedImage;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.BorderUIResource;
import javax.swing.table.*;
import javax.imageio.ImageIO;
import java.io.*;
import java.io.IOException;
import java.util.*;

public class brickout {
	static final int FPS = 50;
	static final int timeStep = 1000/FPS;
	static final int ScreenWidth = 1000, ScreenHeight = 1000;
	
	public static void main(String[] args){	
		boolean showSplash = true;
		JFrame frame = new JFrame("BrickOut - By: Marco");
		GUI gui = new GUI(ScreenWidth, ScreenHeight, null);
		SplashScreen splash = new SplashScreen(ScreenWidth, ScreenHeight);
		
		frame.addComponentListener(new ComponentAdapter(){ //deal with resizing of the window
			@Override
			public void componentResized(ComponentEvent e){						
				frame.setSize(frame.getWidth(), frame.getWidth() + 22);
			}
		});
		frame.add(splash);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setFocusTraversalKeysEnabled(false);
		frame.pack();
		frame.setVisible(true);
		
		while (true){ //master loop
			try{
				if(showSplash){ //Splash Loop
					if(!splash.showing()){ //replace splash with gui
						frame.remove(splash);
						frame.add(gui, BorderLayout.CENTER);
						frame.pack();
						showSplash = false;
					}
					splash.repaint();
				}
				else{ //Game Loop		
					if(gui.replay){ //get saved settings and create new game from those settings
						Object[] savedSettings = gui.getSavedSettings();
						gui.settings.frame.dispose();
						frame.remove(gui);
						gui = new GUI(ScreenWidth, ScreenHeight, savedSettings);
						frame.add(gui, BorderLayout.CENTER);
						frame.pack();
					}
					
					while(gui.gameOver == false){ //active game loop
						try{
							gui.move();
							gui.repaint();
							Thread.sleep(timeStep); 
						} catch (InterruptedException e) { }
						if(gui.gameOver){
							gui.createAndDisplayHighScores();
							gui.revalidate();	
						}
					}
				}
				Thread.sleep(timeStep * 50);
			} catch (InterruptedException e) { }
		}
	}
}
	
class GUI extends JPanel implements MouseListener, MouseMotionListener {
	static int screenWidth, screenHeight; 
	int rows = 2, cols = 7, margin = 4, frames = 0, fps = 0, bgIndex = 1;
	int level = 1, score = 0, lives = 3, storedPups = 0, ballMultiplier = 3, timeSinceLastBuff = 0, numLocked = 0;
	Point mousePos = null;
	BufferedImage buffer, background, settingsIcon;
	Graphics2D bg;
	double ballsize = 30, brickheight, gameScale =  .75, fpsTimer, startTime, gameTimer = 0, buffTimer = 0, paddleVX = 0;
	boolean paused = true, gameOver = false, replay = false, showDetails = true, cheatsActive = false;
	boolean lockedEnabled = true, hsEligible = !cheatsActive, toggledLockedOff = !lockedEnabled;
	Settings settings;
	Rectangle2D bottomBar, intersection;
	Paddle paddle;
	ArrayList<Ball> balls = new ArrayList<Ball>();
	ArrayList<ArrayList<Brick>> bricks = new ArrayList<ArrayList<Brick>>();
	ArrayList<Brick> exposedBricks = new ArrayList<Brick>();
	ArrayList<PowerUp> pups = new ArrayList<PowerUp>();
	java.util.List<String> cheatsList = Arrays.asList("Paddle Speed ↑ / Ball Angle", "Paddle Speed ↓ / Ball Angle", "Toggle Paddle AutoMove", "Spawn Balls", "Spawn PowerUp");
	Map<KeyStroke, String> savedCheats = new HashMap<KeyStroke, String>();
	ArrayList<Tuple> speedInfo = new ArrayList<Tuple>();
	PowerUp activePup;
	Color colors[] = { new Color(220, 0, 0), new Color(250, 150, 0), new Color(255, 209, 0), 
					   new Color(0, 220, 0), new Color(0, 220, 200), new Color(0, 0, 220), new Color(150, 0, 200) };
	Font gameFont = new Font("Courier", Font.PLAIN, 32);
	Random roll = new Random();
	
	public GUI(int sW, int sH, Object[] savedSettings){
		addMouseListener(this);
		addMouseMotionListener(this);
		screenWidth = sW;
		screenHeight = sH;
		
		bottomBar = new Rectangle2D.Double(0, screenHeight - 80, screenWidth, 80);
		paddle = new Paddle();
		paddleVX = Math.abs(paddle.vx); //always positive
		balls.add(new Ball( ballsize/2 ));
		brickheight = this.populateBricks(); //populate bricks and get brickheight
		try{ //initialize images
			background = ImageIO.read(GUI.class.getResourceAsStream("images/BG1.jpg"));
			settingsIcon = ImageIO.read(GUI.class.getResourceAsStream("images/settingsIcon.png"));
		}
		catch(IOException e){ System.out.println("File Not Found"); }
		
		this.initializeKeyBindings();
		if(savedSettings != null)
			setSavedSettings(savedSettings); //if on replay, initialize the previous game settings
		else
			settings = new Settings(this);
		
		buffer = new BufferedImage(screenWidth * 2, screenHeight * 2, BufferedImage.TYPE_INT_RGB);
		bg = buffer.createGraphics();
		bg.scale(gameScale, gameScale);
		bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		
		setPreferredSize(new Dimension((int)(screenWidth * gameScale), (int)(screenHeight * gameScale)));		
		addComponentListener(new ComponentAdapter(){
			@Override
			public void componentResized(ComponentEvent e){
				Rectangle b = e.getComponent().getBounds();
				bg.scale(1/gameScale, 1/gameScale);
				gameScale = b.width/(double)screenWidth;
 				bg.clearRect(0, 0, getWidth(), getHeight());
				bg.scale(gameScale, gameScale);
			}
		}); //handle resizing of the window
		
		fpsTimer = System.currentTimeMillis();
		startTime = fpsTimer;
	}

	public void move(){
		if (!paused && !gameOver){
			if(System.currentTimeMillis() - startTime >= 10) //increase gameTimer every 1/100th of a second
				gameTimer++;
			
			timeSinceLastBuff++;
			if(timeSinceLastBuff > 5400 * 2){ //spawn a powerup after 2 minutes without one
				pups.add(new PowerUp(screenWidth/2, margin*(rows+1) + brickheight*rows + brickheight/2, PowerUp.Type.randomType()));
				timeSinceLastBuff = 0;
			}
			
			if(gameTimer >= buffTimer - 100 && activePup != null) //buffTimer - 100 so deactivates after 1 not 0
				deactivateBuff();
			
			paddle.move();
			balls.forEach( ball ->{
				ball.move();
				if(ball.stuck)
					ball.stickTo(paddle);
			});
			
			//handles ball going offscreen, hitting the paddle, and hitting a brick
			for(Ball ball : balls){
				if(ball.y - ball.rad >= bottomBar.getY() || ball.y + ballsize < 0){ //lose a life scenario
					loseALife(ball);
					return;
				}
				else if(paddle.thisPaddle.intersects(ball.thisBall.getBounds()) && ball.stuck == false){ //ball hits the paddle while moving/not stuck
					ball.hitThePaddle(paddle.width - (ball.x - paddle.thisPaddle.getX()), paddle.width); //gives the ratio d/l to determine where the ball hit the paddle
					ball.changeColor();
					if(paddle.sticky){ //ball hits a sticky paddle
						ball.stuck = true;
						paddle.hasBallStuck = true;
						ball.dx = paddle.x - ball.x; //used to track ball location relative to paddle
						ball.y = paddle.thisPaddle.getY() - ball.rad;
					}
				}
				else if(ball.y - ball.rad <= (rows*brickheight) + margin*(rows+1)){ //check ball hitting a brick only if near bricks
					for( Brick brick : exposedBricks ){
						if(brick.active && brick.thisBrick.intersects(ball.thisBall.getBounds())){ //only hit active bricks
							
							intersection = brick.thisBrick.createIntersection(ball.thisBall.getBounds());
							
							//powerballs go through bricks
							if(!ball.power){
								int bRow = brick.row;
								int bCol = (brick.col + 1) >= cols ? brick.col : brick.col + 1;
							
								if( intersection.getHeight() > intersection.getWidth() && //hit the side
									(bricks.get(bRow).get(bCol).active == false	|| intersection.getX() == brick.x) )//hit right or left
									ball.vx = -ball.vx;
								else if (Math.abs(intersection.getHeight() - intersection.getWidth()) < .5){ //hit the corner of a brick
									ball.vy = -ball.vy;
									ball.vx = -ball.vx;
								}
								else //hit top or bottom
									ball.vy = -ball.vy;
							}
								
							if(brick.locked && brick.thisBrick.contains(ball.x, ball.y)){
								ball.y += brickheight;	
								ball.vy = -1 * Math.abs(ball.vy);	
							}
							
							ball.changeColor();
							
							if(!brick.locked) //change color/type of the hit brick
								brick.type--;
							
							if(ball.power){ //powerballs only
								brick.deactivate();
								removeExposedBrick(brick);
								score += 10;
							}
							else if(brick.type  == -1){ //deactivate brick
								brick.deactivate();
								removeExposedBrick(brick);
								rollBuff(brick);
								score += 10;
							}
							else if (!brick.locked) //change color of brick if not removed
								brick.setColor(colors[brick.type]);
							
							return;
						}
					}
				}			
			}
			
			if( pups.size() > storedPups ){ //got moving pups
				for( PowerUp pup : pups ){
					pup.move();
					if(pup.pupRect.intersects(paddle.thisPaddle) && storedPups < 3){ //store pups collected by paddle if stored pups < 3
						pup.stored = true;
						pup.vy = 0;			//change location of stored pups
						pup.setRect(screenWidth/2 + 100 + (storedPups * pup.width * 1.5), bottomBar.getY() + pup.height/2);
						storedPups++;
					}
				}
				//remove powerups that go past the bottom bar
				pups.removeIf( pup -> (pup.y + pup.height/2 >= bottomBar.getY() && pup.vy != 0));
			}
			
		}
		else if(paused){
		}
	}

	@Override
	public void paintComponent(Graphics gg){
		
		frames++; //increase the number of frames drawn every time we repaint
		if(System.currentTimeMillis() - fpsTimer >= 1000){ //Track Live Frames per Second
			fps = frames;
			frames = 0;
			fpsTimer = System.currentTimeMillis();
		}
	
		bg.setBackground(Color.white); //only seen if the background image fails to load
		bg.clearRect(0, 0, screenWidth, screenHeight);
		bg.drawImage(background, 0, 0, screenWidth, screenHeight, this);
		
		bg.setColor(Color.black); //draw the status (bottom) bar
		bg.fill(bottomBar);
		bg.setColor(colors[2]); //gold
		bg.setFont(gameFont);
		bg.drawString("Lives:", 10, (int)bottomBar.getY()  + getFontSize(bg));
		bg.drawString("PowerUps: " , screenWidth/2 - 100, (int)bottomBar.getY()  + getFontSize(bg));
		bg.drawString("Score: " + score, screenWidth - 250, (int)bottomBar.getY()  + getFontSize(bg));
		
		if( lives <= 5){
			for( int i = 0; i < lives; i++){
				bg.setColor(balls.get(0).ballColor);
				bg.fill(new Ellipse2D.Double(130.0 + i*ballsize*1.5, bottomBar.getY() + (getFontSize(bg) - ballsize)/2 + 2, ballsize, ballsize));
			}
		}  //display player lives
		else{
			bg.setColor(balls.get(0).ballColor);
			bg.fill(new Ellipse2D.Double(130.0, bottomBar.getY() + (getFontSize(bg) - ballsize)/2 + 2, ballsize, ballsize));
			bg.setColor(colors[2]);
			bg.drawString("x" + lives, 133 + (int)ballsize, (int)bottomBar.getY()  + getFontSize(bg));
		} //more than 5 lives, show as a "Lives: ball x6"
		
		paddle.paint(bg); //draw paddle
		if(activePup != null){
			bg.setColor(glowingColor(Color.yellow)); //draw glowing border around the active powerup
			bg.fill(new Rectangle2D.Double(activePup.pupRect.getX() - margin, activePup.pupRect.getY() - margin, 
						activePup.pupRect.getWidth() + margin*2, activePup.pupRect.getHeight() + margin*2));	
			paintActivePup(); //display active powerup. Make it flash when there are only 5 seconds left
			bg.setColor(Color.white);
			bg.setFont(new Font("Courier", Font.BOLD, 30)); //display the timer for how long the powerup is active
			bg.drawString("" + (int)(buffTimer - gameTimer)/100, (float)(activePup.x + activePup.width*.67), (float)(activePup.y + activePup.height*.2));
			
			if(mousePos != null && activePup.pupRect.contains(mousePos)){
				int centerX = getCenterForMaxFont((int)(bottomBar.getHeight()/2), activePup.typeToString(getActionKeyBind("Action")), getFontSize(bg));
				bg.setColor(colors[2]);
				bg.drawString(activePup.typeToString(getActionKeyBind("Action")), centerX, (float)(screenHeight - getFontSize(bg)/2));	
			} //draw powerup hover text
		} //draw active powerup
		for(int i = 1; i <= storedPups; i++){
			bg.setColor(Color.white);
			bg.setFont(new Font("Courier", Font.BOLD, 30));
			PowerUp p = getStoredPup(i); //gets the stored powerup at that index
			bg.drawString("" + i, (float)(p.x - p.width * .2), (float)(p.y - p.height * .667)); //number above the stored pup
			bg.fill(new Rectangle2D.Double(p.pupRect.getX() - margin, p.pupRect.getY() - margin, 
						p.pupRect.getWidth() + margin*2, p.pupRect.getHeight() + margin*2)); //draws border
						
			if(mousePos != null && p.pupRect.contains(mousePos)){
				bg.setColor(colors[2]);
				int centerX = getCenterForMaxFont((int)(bottomBar.getHeight()/2), p.typeToString(getActionKeyBind("Action")), getFontSize(bg));
				
				bg.drawString(p.typeToString(getActionKeyBind("Action")), centerX, (float)(screenHeight - 5));	
				
			} //draw powerup hover text
		
		} //action bar number and border
		pups.forEach( pup -> pup.paint(bg) ); //draw all powerups
		bricks.forEach( bList -> bList.forEach( brick -> {
			if(brick.active){
				bg.setColor(Color.black);
				bg.fill(new Rectangle2D.Double(brick.x - margin, brick.y - margin, brick.width + margin*2, brick.height + margin*2));
				brick.paint(bg);
			}
		})); //draw black brick border
		try{ balls.forEach( ball -> ball.paint(bg) ); } //draw balls. Catch ConcurrentModExcep when dealing with multiple balls
		catch(ConcurrentModificationException e){ System.out.println("Caught ConcurrentModificationException"); }
	
		if(paused){
			bg.setColor(Color.red);
			bg.setFont(gameFont.deriveFont(Font.BOLD, 120));
			drawCentered("PAUSED", screenHeight/2 - 120);
			bg.setFont(gameFont);
			drawCentered("(Press " + getActionKeyBind("Action") + " or " + getActionKeyBind("Pause") + ")", screenHeight/2 - 60);
			bg.setFont(new Font("Apple Chancery", Font.ITALIC, 200));
			bg.drawString("Level " + level, screenWidth/2 - 300, (int)bottomBar.getY() - 200);
			
			if(settingsIcon != null) //draw settings icon from image if exists
				bg.drawImage(settingsIcon, (int)settings.icon.getX(), (int)settings.icon.getY(), (int)settings.icon.getWidth(), (int)settings.icon.getHeight(), this);
			else{ //draw generic settings icon if image DNE
				bg.setColor(Color.white);
				bg.fill(settings.icon);
				bg.setColor(Color.black);
				bg.setFont(new Font("Courier", Font.PLAIN, 60));
				bg.drawString("S", (float)(settings.x + (settings.width - bg.getFontMetrics().stringWidth("S"))/2), (float)( settings.y + settings.height - 10 ));
			}
		} //Draw paused message and settings icon
		if(gameOver){
			bg.clearRect(0,0,screenWidth,screenHeight);
			bg.setColor(Color.red);
			bg.drawImage(background, 0, 0, screenWidth, screenHeight, this);
			bg.setFont(gameFont.deriveFont(Font.BOLD, 120));
			drawCentered("GAME OVER", (int)(screenHeight * .25) );
			bg.setFont(gameFont);
			drawCentered("(Press " + getActionKeyBind("Replay") + " to Replay)", (int)(screenHeight * .3) );
			bg.setColor(Color.black);
			bg.fill(bottomBar);
			bg.setColor(colors[2]); //gold
			bg.drawString("Lives:", 10, (int)bottomBar.getY()  + getFontSize(bg));
			bg.drawString("PowerUps: " , screenWidth/2 - 100, (int)bottomBar.getY()  + getFontSize(bg));
			bg.drawString("Score: " + score, screenWidth - 250, (int)bottomBar.getY()  + getFontSize(bg));
			
			if(settingsIcon != null) //draw settings icon from image if exists
				bg.drawImage(settingsIcon, (int)settings.icon.getX(), (int)settings.icon.getY(), (int)settings.icon.getWidth(), (int)settings.icon.getHeight(), this);
			else{ //draw generic settings icon if image DNE
				bg.setColor(Color.white);
				bg.fill(settings.icon);
				bg.setColor(Color.black);
				bg.setFont(new Font("Courier", Font.PLAIN, 60));
				bg.drawString("S", (float)(settings.x + (settings.width - bg.getFontMetrics().stringWidth("S"))/2), (float)( settings.y + settings.height - 10 ));
			}
		} //Draw Game Over Screen
		
		if(showDetails){
			bg.setFont(gameFont.deriveFont(27f));
			bg.setColor(Color.white);
			bg.drawString(" FPS:" + fps +  " GT:" + getTime() +  " Speed:" + //Display FPS, GameTime, and Speed of paddle/ball
							settings.speedSlider.getValue(), 0, (int)bottomBar.getY() - 5);
			
			if(paddle.hasBallStuck) //show the "launch the ball" text
				drawCentered("Press " + getActionKeyBind("Action") + " to launch the ball!", (int)(paddle.thisPaddle.getY() - 50));
				
			bg.setFont(gameFont.deriveFont(20f));
			if(mousePos != null) //display mouse position
				bg.drawString("(" + (int)(mousePos.getX()*gameScale) + ", " + (int)(mousePos.getY()*gameScale) + ")", 
								5, (int)bottomBar.getY() - 30);
		} //display the "Show Details
		
		Graphics2D g = (Graphics2D) gg;
		g.drawImage(buffer, null, 0, 0);
	}

	//Game Setup Methods
	public void initializeKeyBindings(){
		InputMap inputs = getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW);
		ActionMap actions = getActionMap();
		
		inputs.put(KeyStroke.getKeyStroke("1"), "Activate PowerUp 1");
		inputs.put(KeyStroke.getKeyStroke("2"), "Activate PowerUp 2");
		inputs.put(KeyStroke.getKeyStroke("3"), "Activate PowerUp 3");
		inputs.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0, true), "Stop Paddle");
		inputs.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0, true), "Stop Paddle");
		inputs.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0, true), "Stop Paddle");
		inputs.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, 0, true), "Stop Paddle");		
		inputs.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0, false), "Move Left");
		inputs.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0, false), "Move Right");
		inputs.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0, false), "Move Left");
		inputs.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, 0, false), "Move Right");
		inputs.put(KeyStroke.getKeyStroke("P"), "Pause");
		inputs.put(KeyStroke.getKeyStroke("SPACE"), "Action");
		inputs.put(KeyStroke.getKeyStroke("X"), "Cancel PowerUp");
		inputs.put(KeyStroke.getKeyStroke("R"), "Replay");
		inputs.put(KeyStroke.getKeyStroke("F"), "Show Details");
		
		//cheats
		inputs.put(KeyStroke.getKeyStroke("UP"), "Paddle Speed ↑ / Ball Angle");
		inputs.put(KeyStroke.getKeyStroke("DOWN"), "Paddle Speed ↓ / Ball Angle");
		inputs.put(KeyStroke.getKeyStroke("V"), "Toggle Paddle AutoMove");
		inputs.put(KeyStroke.getKeyStroke("B"), "Spawn Balls");
		inputs.put(KeyStroke.getKeyStroke("M"), "Spawn PowerUp");
		
		if(!cheatsActive) { toggleCheatBindings(false); } //remove cheats and save them if cheats are off
		
		//regular actions
		actions.put("Stop Paddle", new AbstractAction(){
			public void actionPerformed(ActionEvent e){	
				paddle.keyPressed = false;
				if(!paddle.autoMove)
					paddle.vx = 0;
				else if (paddle.collidingLeftEdge())
					paddle.vx = paddleVX;
				else if(paddle.collidingRightEdge())
					paddle.vx = -paddleVX;
			}
		});	
		actions.put("Move Left", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				if(!paused){
					paddle.keyPressed = true;
					paddle.vx = -1 * paddleVX;
				}
			}
		});
		actions.put("Move Right", new AbstractAction(){
			public void actionPerformed(ActionEvent e){	
				if(!paused){
					paddle.keyPressed = true;
					paddle.vx = paddleVX;
				}
			}
		});
		actions.put("Pause", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				paused = !paused;
				startTime = System.currentTimeMillis();
			}
		});
		actions.put("Action", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				if(paused){
					paused = false; 
					startTime = System.currentTimeMillis();
				}
				else if( paddle.sticky ){
					balls.forEach( ball -> {
						if(ball.stuck){
							ball.stuck = false;
							paddle.hasBallStuck = false;
						}
					});
				}
			}
		});
		actions.put("Cancel PowerUp", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				if(activePup != null)
					deactivateBuff();
			}
		});
		actions.put("Replay", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				if(gameOver)
					replay = true;
			}
		});
		actions.put("Show Details", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				showDetails = !showDetails;
			}
		});	
		actions.put("Activate PowerUp 1", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				if (storedPups >= 1){									
					if(storedPups == 3)
						getStoredPup(3).setRect(getStoredPup(2));
					if(storedPups >= 2)
						getStoredPup(2).setRect(getStoredPup(1));
				
					activateBuff( getStoredPup(1) );
				}
			}
		});	
		actions.put("Activate PowerUp 2", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				if (storedPups >= 2){
					if(storedPups == 3)
						getStoredPup(3).setRect(getStoredPup(2));
				
					activateBuff( getStoredPup(2) );					
				}
			}
		});	
		actions.put("Activate PowerUp 3", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				if (storedPups >= 3)
					activateBuff( getStoredPup(3) );
			}
		});	
	
		//cheat actions
		actions.put("Paddle Speed ↑ / Ball Angle", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				if(paddle.hasBallStuck){
					for( Ball ball : balls ){
						if(ball.stuck){
							if (ball.ratio > 0.0)
								ball.ratio -= .1;
							else
								ball.ratio = 0.0;
							
							ball.hitThePaddle(ball.ratio);
						}
					}
				}
				else if(paddleVX < 10)
					settings.speedSlider.setValue((int)(paddleVX * 10) + 5);
			}
		});
		actions.put("Paddle Speed ↓ / Ball Angle", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				if(paddle.hasBallStuck){
					for( Ball ball : balls ){
						if(ball.stuck){
							if (ball.ratio < 1.0)
								ball.ratio += .1;
							else
								ball.ratio = 1.0;
						
							ball.hitThePaddle(ball.ratio);
						}
					}
				}
				else if(paddleVX > 3.5)
					settings.speedSlider.setValue((int)(paddleVX * 10) - 5);
			}
		});
		actions.put("Toggle Paddle AutoMove", new AbstractAction(){
			public void actionPerformed(ActionEvent e){			
				//setAutoMove( !paddle.autoMove );
				settings.autoMove.doClick();
			}
		});	
		actions.put("Spawn Balls", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				Ball newBall = new Ball(ballsize/2, paddle.x, paddle.y - paddle.height);
				newBall.vy = -Math.hypot(balls.get(0).vx, balls.get(0).vy);
				newBall.vx = 0;
				balls.add(newBall);
			}
		});
		actions.put("Spawn PowerUp", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				//loseALife(balls.get(0));
				//changeLevel();
				pups.add(new PowerUp(screenWidth/2, margin*(rows+1) + brickheight*rows + brickheight/2, PowerUp.Type.randomType()));
				timeSinceLastBuff = 0;

			}
		});		
	}	//Create the initial keybindings and actions. May be overridden by Saved Settings
	public boolean updateKeyBind(KeyStroke oldKey, KeyStroke newKey){
		InputMap inputs = getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW);
		KeyStroke oldKeyModded = KeyStroke.getKeyStroke(oldKey.getKeyCode(), InputEvent.CTRL_DOWN_MASK + InputEvent.SHIFT_DOWN_MASK);
		
		try{
			if( inputs.get(oldKey) != null ){
				inputs.put(newKey, inputs.get(oldKey));
				
				if(inputs.get(oldKeyModded) != null){
					inputs.put(oldKey, inputs.get(oldKeyModded));
					inputs.remove(oldKeyModded);
					settings.setUpControlList(); //update control list
				}else
					inputs.remove(oldKey);
				
				return true;
			}
		}catch(NullPointerException e) { System.out.println("That key does not exist in the Input Map"); }
		
		return false; //let the table know not to update because inputmap didnt change
	} //method used by Settings to replace keybindings
	public void toggleCheatBindings(boolean active){
		InputMap inputs = getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW);
		cheatsActive = active;
		
		if(!cheatsActive){ //cheats off
			savedCheats.clear(); //clear the previously saved cheat bindings
			for(KeyStroke key : inputs.keys()){ //add the bindings for cheats to savedCheats map
				if(cheatsList.contains(inputs.get(key))){
					savedCheats.put(key, (String)inputs.get(key));
					inputs.remove(key); //remove cheat from input map
				}
			}
		}else {	//cheats on
			savedCheats.forEach((k, v) -> { //add saved cheats back into input map 
				KeyStroke unmoddedKey = KeyStroke.getKeyStroke(k.getKeyCode(), 0); 
				if(inputs.get(unmoddedKey) == null) //we check if an unmodded key exists, if not we add unmodded key
					inputs.put(unmoddedKey, v);
				else //if an unmodded key exists, we add a modded version
					inputs.put(KeyStroke.getKeyStroke(k.getKeyCode(), InputEvent.CTRL_DOWN_MASK + InputEvent.SHIFT_DOWN_MASK), v);
			});
		}
	} //remove cheat bindings and save them, or add them to inputmap
	public Object[] getSavedSettings(){
		InputMap inputs = getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW);
		Map<KeyStroke, Object> im2 = new HashMap<KeyStroke, Object>(); 
		for(KeyStroke key : inputs.keys()) //make a copy of all the inputs in InputMap with <key, value>
			im2.put(key, inputs.get(key));
		
		//Save paddle color, speed, automove, cheatsActive status, locked bricks status, 
		//"dont ask again" status, inputmap, gamescale, and showdetails status
		Object[] savedSettings = { new Color(paddle.paddleColor.getRGB()), Double.valueOf(paddleVX), 
								   Boolean.valueOf(paddle.autoMove), Boolean.valueOf(cheatsActive), Boolean.valueOf(lockedEnabled), 
								   Boolean.valueOf(settings.dontAsk), im2, Double.valueOf(gameScale), Boolean.valueOf(showDetails) };
		return savedSettings;
	} //save the configurations of this game for future games
	@SuppressWarnings("unchecked")
	public void setSavedSettings(Object[] savedSettings){
		gameScale = (Double)savedSettings[7];
	
	
		paddle.paddleColor = (Color)savedSettings[0]; //set paddle color
		setDifficulty((Double)savedSettings[1] * 10); //set speed of paddle and balls
		
		setAutoMove((Boolean)savedSettings[2]); //set automove value
		cheatsActive = (Boolean)savedSettings[3]; //set cheatsActive value
		setLocked((Boolean)savedSettings[4]); //set if we have locked bricks
		showDetails = (Boolean)savedSettings[8]; //set if we want to keep displaying details
		
		if(savedSettings[6] != null){ //set current input map to the saved input map
			Map<KeyStroke, Object> im = (Map<KeyStroke, Object>) savedSettings[6];
			this.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).clear(); //clear the current input map
			
			for(KeyStroke key : im.keySet()) //populates inputmap with saved input map (including cheats)
				this.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(key, im.get(key));
			if(!cheatsActive) { toggleCheatBindings(false); } //remove cheats and save them if cheats are off
		} //set the old input map as our input
		
		settings = new Settings(this); //create a new settings window
		settings.dontAsk = (Boolean)savedSettings[5]; //set the dont ask boolean of the new settings window
		
	} //implement the saved configurations from past games into this game
	
	//high scores methods
	public void createAndDisplayHighScores(){
		InputStream highscores;
		highscores = GUI.class.getResourceAsStream("highscores.csv");
		
		String headerData[] = {"Name", "Score", "Level", "Time", "Avg Speed", "Locked?"}; //Default to Blank Header Data
		Object[][] hsData = { {"-", Integer.valueOf(0), "-", "-", "-" ,"-"} ,  //Default to Blank High Score Data
							  {"-", Integer.valueOf(0), "-", "-", "-" ,"-"} ,
							  {"-", Integer.valueOf(0), "-", "-", "-" ,"-"} ,
							 // {"", Integer.valueOf(9999999), "21", "9999:99:99", "100" ,"Yes"} ,
							  {"-", Integer.valueOf(0), "-", "-", "-" ,"-"} ,
							  {"-", Integer.valueOf(0), "-", "-", "-" ,"-"} ,
							  {"-", Integer.valueOf(0), "-", "-", "-" ,"-"} ,
							  {"-", Integer.valueOf(0), "-", "-", "-" ,"-"} 
							};
		
		if(highscores != null){
			Scanner reader = new Scanner(highscores);
				
			if(reader.hasNextLine()) { headerData = reader.nextLine().split(","); }
				
			int row = 0;
			while(reader.hasNextLine()){
				String linedata[] = reader.nextLine().split(","); //get the line data split by the comma
				for( int col = 0; col < linedata.length; col++){
					switch(col){ //populate the table data arrays row by row
						case 0: hsData[row][col] = linedata[col]; break; //Name
						case 1: hsData[row][col] = linedata[col].equals("-") ? Integer.valueOf(0) : Integer.valueOf(linedata[col]); break; //Score
						case 2: hsData[row][col] = linedata[col].equals("-") ? "-" : Integer.valueOf(linedata[col]); break; //Level
						case 3: hsData[row][col] = linedata[col]; break; //Time
						case 4: hsData[row][col] = linedata[col].equals("-") ? "-" : Double.valueOf(linedata[col]);	break; //Speed
						case 5: hsData[row][col] = linedata[col]; break; //Locked Bricks
					}
				}
				row++; //at the end of the column, move to the next row
			} //populate high score table data
			
			reader.close();
		}
		
		int rowIndex = -1; //row index of new high score
		if(hsEligible && score > (Integer)hsData[6][1]){ //new high score if eligible and score > lowest score on table
			newHighScore(hsData);
			for(int i = 0; i < hsData.length; i++){
				if( ((String)hsData[i][0]).equals("...") ){
					rowIndex = i;
					break;
				}
			}
		}
		JTable hsTable = setUpTable(hsData, headerData); //create a custom JTable with the data
		JLabel hsTitle = new JLabel("High Scores"){ 
			public boolean isOpaque(){
				setFont(new Font("Apple Chancery", Font.PLAIN, (int)(80 * gameScale)));
				setForeground(colors[2]);
				setHorizontalAlignment(SwingConstants.CENTER);
				return false;
			}
		}; //Create a custom JLabel as a Title for our Table
		JPanel tablePanel = new JPanel(){
			@Override
			public boolean isOpaque(){
				add(hsTitle);
				add(hsTable.getTableHeader());
				add(hsTable);
				return false;
			}
		}; //Create a JPanel to hold our title, tableheader, and table		
		
		//Create an invisible box to move our table below the Game Over text
		Component invisBox = Box.createRigidArea(new Dimension(1, (int)(screenHeight * gameScale * .35)));
		
		setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
		//add our high scores and invisible box to our GUI
		add(invisBox);
		add(tablePanel);
		revalidate(); //revalidate is necessary to see the components we just added
		
		//Adjust the size of our text (and table) based off the changing sizes of our window
		tablePanel.addComponentListener(new ComponentAdapter(){
			@Override
			public void componentResized(ComponentEvent e){
				hsTable.setFont(gameFont.deriveFont(25f * (float)gameScale)); //adjust table font
				hsTable.getTableHeader().setFont(gameFont.deriveFont(34f * (float)gameScale)); //adjust header font
				hsTitle.setFont(new Font("Apple Chancery", Font.PLAIN, (int)(80 * gameScale))); //adjust title font
				resizeTableCols(hsTable);
				invisBox.setPreferredSize(new Dimension(1, (int)(screenHeight * gameScale * .35)));
			}
		});

		//Take care of the missing name on the new high score
		if(rowIndex != -1){
			int tableWidth = hsTable.getPreferredSize().width - hsTable.getColumnModel().getColumn(0).getPreferredWidth();
			
			JLabel inputLabel = new JLabel("Enter Name Below:"){
				@Override
				public Font getFont(){
					setHorizontalAlignment(SwingConstants.CENTER);
					return new Font("Apple Chancery", Font.BOLD, 20);
				}
			
			}; //Our message to get the name
			
			//Loop until we reach a valid name input
			String name = JOptionPane.showInputDialog(this, inputLabel, "New High Score!!", JOptionPane.QUESTION_MESSAGE);
			while( !isHighScoreNameValid(name, this.getPreferredSize().width - tableWidth)){
				inputLabel.setForeground(Color.red);
				if(name == null || name.isEmpty()){ //update the label depending on name entered
					inputLabel.setText("Enter Name Below:");
					inputLabel.setForeground(Color.black);
				} 
				else if (!name.matches( "(\\w[\\w\\s]*\\w)|\\w"))
					inputLabel.setText("Incorrect Name Format");
				else
					inputLabel.setText("Incorrect Name Length");
				
				name = JOptionPane.showInputDialog(this, inputLabel, "New High Score!!", JOptionPane.QUESTION_MESSAGE);
			}
			
			hsTable.setValueAt(name, rowIndex, 0); 	//update the "..." to a valid name
			resizeTableCols(hsTable);				//resize the table to accomodate new name
			updateHighScoresFile(hsData);			//now that the table data is correct, update the file
		}
		
	} //handles the creation, addition, and resizing of our High Scores Table/Title
	public JTable setUpTable(Object[][] hsData, String[] headerData){
		JTable table = new JTable(hsData, headerData){
			@Override
			public boolean isCellEditable(int row, int col){ //no cells are editable
				return false;
			}
			@Override
			public boolean isCellSelected(int row, int col){
				return false;
			}
		}; //Cells are non-editable and non-selectable
		
		table.setOpaque(false); //set transparency
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.setShowGrid(false);
		table.setCellSelectionEnabled(false);
		table.setFont(gameFont.deriveFont(25f * (float)gameScale));
		table.setForeground(colors[2]); //set text color to gold
		((DefaultTableCellRenderer)table.getDefaultRenderer(Object.class)).setBackground(new Color(0f, 0f, 0f, 0.5f));
		
		JTableHeader header = table.getTableHeader();
		header.setResizingAllowed(false);
		header.setReorderingAllowed(false);
		((DefaultTableCellRenderer)header.getDefaultRenderer()).setHorizontalAlignment(SwingConstants.CENTER);
		header.setOpaque(false); //set transparency
		header.setBackground(new Color(1.0f, 1.0f, 1.0f, 0.1f));
		header.setFont(gameFont.deriveFont(34f * (float)gameScale));
		header.setForeground(colors[2]); //set text color to Gold
		
		resizeTableCols(table); //resize table columns for current font size
		
		return table;
	} //Creates a custom table of high scores from given data
	public void resizeTableCols(JTable table){
		TableColumnModel model = table.getColumnModel();
		JTableHeader header = table.getTableHeader();
		FontMetrics tableFM = table.getFontMetrics(table.getFont());
		
		for(int col = 0; col < table.getColumnCount(); col++){ //resize columns
			int width = 15; // Min width
			for (int row = 0; row < table.getRowCount(); row++) { //get largest width among the rows
				Object val = table.getValueAt(row, col);
				int colWidth = tableFM.stringWidth( val instanceof String == false ? val.toString() : (String)val );
				
				width = Math.max(colWidth , width);
			}
			
			//see if the header width is larger
			FontMetrics headerFontMetrics = header.getFontMetrics(header.getFont());
			int headerWidth = headerFontMetrics.stringWidth(table.getColumnName(col));
 			width = Math.max(headerWidth + 1 , width);
		
			model.getColumn(col).setPreferredWidth(width + 10);
			if( col == 1 || col > 3) {
				model.getColumn(col).setCellRenderer(new DefaultTableCellRenderer(){
					@Override
					public Color getBackground(){
						setHorizontalAlignment(SwingConstants.CENTER);	
						return new Color(0f, 0f, 0f, 0.5f);
					}
				}); 
			}
		}
		
		model.setColumnMargin(5);
		table.setRowHeight(tableFM.getHeight() + 5);	
	} //auto-sizes columns/table by largest column widths
	public void newHighScore(Object[][] hsData){
		//Data for the new highscore. Name to be entered after to account for tableWidth changes
		Object newHS[] = { "...", Integer.valueOf(score), Integer.valueOf(level), getTime(), 
							Double.valueOf(getAvgSpeed()), toggledLockedOff ? "No" : "Yes" };
		for(int i = 0; i < newHS.length; i++)
			hsData[6][i] = newHS[i]; //replace the last row
		
		Arrays.sort(hsData, (a, b) -> Integer.compare((Integer)(b[1]), (Integer)(a[1]))); //sort the table be score (decreasing)
	}	//Replaces the last high score with the new one and sorts it
	public boolean isHighScoreNameValid(String name, int availableWidth){
		
		if(name == null || name.isEmpty() || !name.matches( "(\\w[\\w\\s]*\\w)|\\w"))
			return false; //regex states either a single letter or multiple letters/nums/spaces that begin and end in a letter
		
		FontMetrics tableFM = getFontMetrics(gameFont.deriveFont(25f * (float)gameScale));
		int nameWidth = tableFM.stringWidth(name); //get the size of the name
		//check if the name fits in the available space
		if(nameWidth >= availableWidth - 5)
			return false;
	
		return true;
	} //returns true if a valid name
	public void updateHighScoresFile(Object[][] hsData){	
		try(FileWriter f = new FileWriter("highscores.csv"); 
			BufferedWriter b = new BufferedWriter(f); PrintWriter p = new PrintWriter(b);) {
			
			p.println("Name,Score,Level,Time,Avg Speed,Locked?"); //header
			
			for(int i = 0; i < hsData.length; i++){
				for(int j = 0; j < hsData[i].length; j++){
					if(j != hsData[i].length - 1) //not last, add a comma
						p.print("" + hsData[i][j] + ",");
					else //last element, forget the comma
						p.println(hsData[i][j]);				
				}
			}
			
			p.close();
				
		} catch(IOException ex) {System.out.println("Couldn't Create New HighScores File");}	
	} //Creates/Overwrites the highscores file
	
	//utility methods
	public String getTime(){
		int minutes = (int)(gameTimer/6000);
		int seconds = (int)(gameTimer % 6000) /100 ;
		int milliseconds = (int)(gameTimer % 100);
		String ms = "" + milliseconds;
		if(milliseconds < 10) //guarantees milliseconds has two digits
			ms = "0" + milliseconds;
		
		return minutes + ":" + seconds + ":" + ms;
	} //Return a String Representation of Game Timer
	public void drawCentered(String str, int y){
		int centerX = (screenWidth - bg.getFontMetrics().stringWidth(str))/2;
		bg.drawString(str, centerX, y);
	} //calculates the X value required for centered text and draws it
	public int getCenterForMaxFont(int height, String str, int fontsize){
		bg.setFont(gameFont.deriveFont((float)fontsize));
		FontMetrics metrics = bg.getFontMetrics();
		int centerX = (screenWidth - metrics.stringWidth(str)) / 2;
		
		if(screenWidth < metrics.stringWidth(str) || metrics.getHeight() > height){
			centerX = getCenterForMaxFont(height, str, fontsize - 1);
		}
		else if(screenWidth - metrics.stringWidth(str) > screenWidth * .1){
			centerX = getCenterForMaxFont(height, str, fontsize + 1);
		}
		
		return centerX;
		
	} //recursively calculates max fontsize and returns the X value to draw the string
	public int getFontSize(Graphics2D b){
		return b.getFont().getSize();
	} //helper method to get current fontsize
	public Color glowingColor(Color c){
		int glowSpeed = 5;
		int max = 256 * 2 / glowSpeed;
		int alpha = (int)gameTimer % max; //0-255 when gameScale = 2
		
		if(alpha > max/2) //at halfway, reverse direction
			alpha = max - alpha;
	
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha * glowSpeed);
	}
	public void paintActivePup(){ //paint active pup. glow if less than 5 seconds left
		if((buffTimer - gameTimer)/100 < 5 ){ bg.setColor(glowingColor(activePup.powerColor)); }
		else{ bg.setColor(activePup.powerColor); }
		bg.fill(activePup.pupRect);
		
		if((buffTimer - gameTimer)/100 < 5 ){ bg.setColor(glowingColor(Color.black)); }
		else{ bg.setColor(Color.black);}
		bg.setFont(new Font("Courier", Font.BOLD, 30));
		bg.drawString(activePup.initials, (int)(activePup.x - activePup.width/2 + 2), (int)(activePup.y + activePup.height*.2));
	}
	public String getActionKeyBind(String action){
		InputMap inputs = getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW);
		KeyStroke key = KeyStroke.getKeyStroke('a');
		for(KeyStroke k : inputs.keys()){
			if(inputs.get(k).equals(action)){
				key = k;
				break;
			}	
		}
		String keyName = KeyEvent.getKeyText(key.getKeyCode()); //Get the KeyText of our KeyStroke
		if (keyName.equals("␣")) { keyName = "Spacebar"; }
		if (keyName.equals("⇥")) { keyName = "Tab"; }
		if (keyName.equals("⇪")) { keyName = "CAPS"; }
		
		return keyName;
	}
	public KeyStroke getKeyBind(String action){
		for(KeyStroke k : getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).keys()){
			if(getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).get(k).equals(action)){
				return k;
			}	
		}
		
		System.out.println("Action not found. Returning null.");
		return null;
	}
	public void addSpeedInfo(){
		double totalTime = speedInfo.stream().mapToDouble(Tuple::getT).sum();
		
		double time = gameTimer - totalTime;
		int speed = !paddle.autoMove ? 0 : (int)(paddleVX * 10);
	
		if(time != 0)
			speedInfo.add(new Tuple(speed, time));
		
		// getAvgSpeed();
// 		System.out.println();
	}
	public double getAvgSpeed(){
		//System.out.println(speedInfo);
		double sum = 0;
		
		for(Tuple tup : speedInfo)
			sum += tup.s * tup.t;
		
		
		double avg = (int)((sum/gameTimer) * 10) / 10.0;
		//System.out.println("" + sum + " / " + gameTimer + " = " + avg);
		return avg;
	}
	
	//game methods
	public double populateBricks(){ //populate bricks array and return brick height
		bricks.clear();
		exposedBricks.clear();
		numLocked = 0;
		for(int i = 0; i < rows; i++){
			bricks.add(new ArrayList<Brick> ());
			int brickType = rows - i - 1;
			
			for(int j = 0; j < cols; j++){
				double w = (screenWidth - (cols + 1)*margin)/ (double)cols;
				Brick temp = new Brick(brickType, w, i, j, margin);
				temp.setColor(colors[brickType]);
				
				int rollLocked = roll.nextInt(50 - brickType*3);
				if(rollLocked == 0 && lockedEnabled){ 
					temp.locked = true; 
					temp.setColor(Color.gray);
					numLocked++;
				}
				
				if( i == rows-1 ){
					exposedBricks.add(temp);
					bricks.get(i).add(temp);
				}
				else
					bricks.get(i).add(temp);
			}
		}
		return bricks.get(0).get(0).height; //used to draw black rect behind bricks
	}
	public void loseALife(Ball ball){
		balls.remove(ball);
		
		if(balls.size() == 0){
			paused = true;
			lives--;
			pups.removeIf( p -> (p.vy != 0));
			
			if( lives >= 0 ){
				balls.add(new Ball(ballsize/2));
				
				if(activePup != null)
					deactivateBuff();
				paddle = new Paddle(paddle.autoMove, paddle.paddleColor);
				setDifficulty((double)settings.speedSlider.getValue());
				//paddle.vx = -1 * Math.abs(paddle.vx);
			}
			else{
				gameOver = true;
				if(!cheatsActive) { //if false make inputmap include cheats for new game then set back to false 
					toggleCheatBindings(true); 
					cheatsActive = false;
				}
				addSpeedInfo();
			}
		}
	}
	public void addNewExposedBrick(Brick b){
		if(!exposedBricks.contains(b) && b.active == true)
			exposedBricks.add(b);
		
	} //adds active brick to exposedBricks if not already added
	public void removeExposedBrick(Brick b){
		//set all bricks around the hit brick to the appropriate exposures
		//add those bricks to exposedBricks if they aren't already added
		//remove hit brick from list
		int r = b.row, c = b.col;
		
		if(r != 0){ //sets brick above hit-brick. protects from index out of bounds error here
			bricks.get(r-1).get(c).exposedSide[2] = true; //bottom is exposed
			addNewExposedBrick(bricks.get(r-1).get(c));
		}
		if(r + 1 != rows){ //brick below
			bricks.get(r+1).get(c).exposedSide[0] = true; //top is exposed
			addNewExposedBrick(bricks.get(r+1).get(c));
		}
		if(c != 0){ //brick to the left
			bricks.get(r).get(c-1).exposedSide[1] = true; //right is exposed
			addNewExposedBrick(bricks.get(r).get(c-1));
		}
		if(c + 1 != cols){ //brick to the right
			bricks.get(r).get(c+1).exposedSide[3] = true; //left is exposed
			addNewExposedBrick(bricks.get(r).get(c+1));
		}
	
		exposedBricks.remove(b);
				
		if(exposedBricks.size() == 0)
			changeLevel();
		for( int i = 0; i < exposedBricks.size(); i++){ //if there are no non-locked bricks left, change level
			if(!exposedBricks.get(i).locked)
				break;
			else if(i == exposedBricks.size() - 1)
				changeLevel();
		}
		
	}
	public void changeLevel(){
		paused = true;
		balls.clear();
		balls.add(new Ball(ballsize/2));
		paddle.setX(screenWidth/2);
		paddle.vx = -paddleVX;
		setDifficulty((double)settings.speedSlider.getValue());
		level++;
		if(lives < 3)
			lives = 3;
		changeBackground();
		timeSinceLastBuff = 0;
		
		if(rows == 7)
			cols++;
		else
			rows++;
		populateBricks();
		repaint();
		revalidate();
	}
	public void changeBackground(){
		if( bgIndex > 5 )
			bgIndex = 1;
		
		bgIndex++;
		try{ 
			background = ImageIO.read(GUI.class.getResourceAsStream("images/BG" + bgIndex + ".jpg")); 
		}
		catch(IOException e){ System.out.println("File Not Found"); }
	}
	public void setBackground (int i){
		int index = (i > 6) ? 1 : i ;
		// if( i > 6 )
// 			index = 1;
// 		else
// 			index = i;
		try{ 
			background = ImageIO.read(GUI.class.getResourceAsStream("images/BG" + index + ".jpg")); 
		}
		catch(IOException e){ System.out.println("File Not Found"); }
	}
	public void rollBuff(Brick b){
		//the longer the timeSinceLastBuff, the more likely to get one.
		//base chance of getting a bonus is 10%
		//Every 15 seconds without a buff, adds 11% up to a max of 98%
		//average timeSinceLastBuff in 15 seconds = 1850
		
		int theRoll = roll.nextInt(100) + 1;
		int bonusMultiplier = 11 * timeSinceLastBuff/1850;
		if(bonusMultiplier > 90)
			bonusMultiplier = 90;
		
		if(theRoll <= 10 + bonusMultiplier){
			pups.add(new PowerUp(b.x + b.width/2, b.y + b.height/2, PowerUp.Type.randomType()));
			timeSinceLastBuff = 0;
		}
		
	}
	public PowerUp getStoredPup(int n){
		int c = 1; //stored pup counter
		
		for( PowerUp p : pups){
			if(p.stored){
				if(c == n)
					return p;
				else
					c++;
			}
		}
		return null;
	}
	public void activateBuff(PowerUp pup){
		if(activePup != null && pup.type != PowerUp.Type.EXTRA_LIFE)
			deactivateBuff();
		
		if(pup.type != PowerUp.Type.EXTRA_LIFE){
			pup.activate();
			activePup = pup;
			storedPups--;
			buffTimer = gameTimer + 1600; //1 gameTimer = 1/100th of a second. so 1600 - 100 = 15 seconds
		}
		
		switch (pup.type){
			case BIG_BALL:
				balls.forEach(ball -> {
					ball.rad = ballsize;
					if(ball.y < screenHeight/2){ ball.y += ballsize; }
					ball.resetFrame();
				});
			break;
			case BIG_PADDLE:
				paddle.setWidth(paddle.width*2);
			break;
			case MULT_BALLS:
				Ball original = balls.get(0);
				for(int i = 0; i < ballMultiplier; i++){
					if(i == 0)
						balls.set(0, new Ball(original, i, ballMultiplier));
					else
						balls.add(new Ball(original, i, ballMultiplier));
				}
	
			break;
			case STICKY_PADDLE:
				paddle.sticky = true;
			break;
			case EXTRA_LIFE:
				lives++;
				storedPups--;
				pups.remove(pup);
			break;			
			case POWER_BALL:
				buffTimer += 1500;
				Ball newBall = new Ball(ballsize * .75, paddle.x, paddle.y - paddle.height/2 - ballsize * .75, true);
				newBall.vy = -Math.hypot(newBall.vx, newBall.vy) / 2 ;
				newBall.vx = 0;
				newBall.power = true;
				balls.add(newBall);
				deactivateBuff();
			break;
		}
	}
	public void deactivateBuff(){
		switch (activePup.type){
			case BIG_BALL:
				balls.forEach(ball -> {
					ball.rad = ballsize/2;
					ball.resetFrame();
				});
			break;
			case BIG_PADDLE:
				paddle.setWidth(paddle.width/2);	
			break;
			case STICKY_PADDLE:
				paddle.sticky = false;
				balls.forEach( ball -> {
					if(ball.stuck){
						ball.stuck = false;
						paddle.hasBallStuck = false;
					}
				});
			break;
		}
	
	
		pups.remove(activePup);
		activePup = null;
		buffTimer = 0;
	}
	
	//settings toggle methods
	public void setDifficulty(double newSpeed){
		addSpeedInfo();
		if(paddle.autoMove)
			paddle.vx = Math.signum(paddle.vx) * newSpeed / 10.0;
		
		paddleVX = newSpeed / 10.0;	
		
		try{
			for(Ball ball: balls){
				double theta = Math.toDegrees(Math.atan2(-ball.vy, ball.vx));
				double dist = Math.hypot(  3 * newSpeed/50, 3 * newSpeed/50  );
				ball.vx = dist * Math.cos(Math.toRadians(theta));
				ball.vy = -dist * Math.sin(Math.toRadians(theta));
			}
		}catch(ConcurrentModificationException e){ System.out.println("Caught ConcurrentModificationException"); }

	}
	public boolean getAutoMove(){ return paddle.autoMove; }
	public void setAutoMove(boolean b){
		addSpeedInfo();
		paddle.autoMove = b; 
		paddle.vx = (b == true) ? -paddleVX : 0;
	}
	public void setLocked(boolean b){
		lockedEnabled = b;
		
		if (b == false){ //Make locked bricks no longer locked
			bricks.forEach( bList -> bList.forEach( brick -> {
				if(brick.active && brick.locked){
					brick.locked = false;
					brick.setColor(colors[brick.type]);
				}
			}));
			toggledLockedOff = true;
		}
		else if(numLocked == 0){ //put in some random locked bricks if none were locked previously
			bricks.forEach( bList -> bList.forEach( brick -> {
				int rollLocked = roll.nextInt(50 - brick.type*3);
				if(brick.active && rollLocked == 0){
					brick.locked = true;
					brick.setColor(Color.gray);
					numLocked++;
				}
			}));
		}
		else{ //put same number of locked bricks as before
			int c = numLocked;
			while (c > 0){
				for( ArrayList<Brick> bList : bricks ){
					if(c > 0){ //don't move to the next row if we reached numlocked
						for(Brick brick : bList){
							int rollLocked = roll.nextInt(50 - brick.type*3);
							if(rollLocked == 0 && c > 0 && brick.locked == false && brick.active){
								brick.locked = true;
								brick.setColor(Color.gray);
								c--;
								break;
							}
						}
					}
					else
						break;
				}
			}
		}
	
	}
	
	//Event methods
	public void mouseClicked(MouseEvent e) {}	
	public void mousePressed(MouseEvent e) {}
	public void mouseReleased(MouseEvent e) {
		if(!paused && storedPups > 0){
			for(int i = 1; i <= storedPups; i++){
				PowerUp p = getStoredPup(i);
				
				//find which PowerUp was clicked
				if(p != null && p.pupRect.contains(e.getX() * 1/gameScale, e.getY() * 1/gameScale)){
					switch (i){
						case 1: //clicked on PowerUp 1
							if(storedPups == 3)
								getStoredPup(3).setRect(getStoredPup(2));
							if(storedPups >= 2)
								getStoredPup(2).setRect(getStoredPup(1));
						break;
						case 2: //clicked on PowerUp 2
							if(storedPups == 3)
								getStoredPup(3).setRect(getStoredPup(2));
						break;
					}
					activateBuff(p);
					break;	
				}
			}
		}
		
		//show settings window when the icon is clicked
		if(paused && settings.icon.contains(e.getX() * 1/gameScale, e.getY() * 1/gameScale))
			settings.frame.setVisible(true);
	}
	public void mouseEntered(MouseEvent e) {}
	public void mouseExited(MouseEvent e) {
		mousePos = null;
	}
	public void mouseDragged(MouseEvent e){}
	public void mouseMoved(MouseEvent e){
		mousePos = e.getPoint();
		mousePos.setLocation(e.getX() * 1/gameScale, e.getY() * 1/gameScale);
	}
}

class Tuple{
	public final int s;
	public final double t;
	
	public Tuple(int s, double t){
		this.s = s;
		this.t = t;
	}
	public double getT(){return t;}
	public String toString(){
		return "(" + s + ", " + t + ")\n";
	}
}

class Paddle{
	Rectangle2D thisPaddle;
	double x, y, vx = -5.0;
	double width = 150, height = 30; 
	double speed = 130/(double)brickout.FPS;
	int screenW, screenH;
	Color paddleColor = Color.getHSBColor(.69f, 1.0f, 1.0f);
	boolean autoMove = true, sticky = false, hasBallStuck = false, keyPressed = false;
	
	public Paddle(){
		this(brickout.ScreenWidth, brickout.ScreenHeight);
	}
	
	public Paddle(boolean auto, Color c){
		this();
		autoMove = auto;
		if(autoMove == false)
			vx = 0;
		paddleColor = c;
	}
	
	public Paddle(int sw, int sh){
		screenW = sw;
		screenH = sh;
		x = screenW/2;
		y = screenH - 50 - (height*3);
		thisPaddle = new Rectangle2D.Double(x - width/2, y - height/2, width, height);
	}
	
	public void move(){
		if ( (collidingLeftEdge() && vx < 0) || (collidingRightEdge() && vx > 0) ){
			if(!keyPressed)
				vx = -vx;
			else
				vx = 0;
		}
		
		x += vx * speed;
		thisPaddle.setFrame(x - width/2, y - height/2, width, height);
	}
	public void setWidth(double w){
		width = w;
		thisPaddle.setRect(x - width/2, y - height/2, width, height);
	}
	public void setX(double x){
		this.x = x;
		thisPaddle.setFrame(x - width/2, y - height/2, width, height);
	}
	
	public boolean collidingRightEdge(){
		return (x + (width/2)) >= screenW;
	}
	public boolean collidingLeftEdge(){
		return (x - (width/2)) <= 0;
	}

	public void paint(Graphics2D g){
		g.setColor(paddleColor);
		g.fill(thisPaddle);
		// g.setFont(new Font("American Typewriter", Font.BOLD, 15));
// 		g.setColor(Color.white);
// 		g.drawString("" + x, (float)x - 15, (float)y);

	}
}

class Ball extends Paddle {
	Ellipse2D thisBall;
	double vy, rad, dx = 0, ratio = 0;
	Random rnd = new Random();
	Color ballColor;
	boolean power = false, stuck = false;
	
	public Ball(){
		this(15.0);
	}
	public Ball(double r){
		this(r, r * 2, (GUI.screenHeight) / 2);
	}
	public Ball(double r, double xC, double yC){
		this(r, xC, yC, false);
	}
	public Ball(double r, double xC, double yC, boolean powerball){
		x = xC;
		y = yC;
		rad = r;
		power = powerball;
		vy = vx = 3.0;
		width = 2*rad;
		thisBall = new Ellipse2D.Double(x - rad, y - rad, width, width);
		changeColor();
	} //Creating Powerballs
	
	public Ball(Ball b, double index, int total){ 
		this(b.rad, b.x, b.y);
		vy = b.vy;
		vx = b.vx;
		double theta = Math.toDegrees(Math.atan2(-vy, vx));

		theta += 10 * (index - ((double)total-1)/2);
		
		double dist = Math.hypot(vy, vx);
		vx = dist * Math.cos(Math.toRadians(theta));
		vy = -dist * Math.sin(Math.toRadians(theta));
	}//Creating Multiballs

	public void changeColor(){
		if(!power)	
			ballColor = Color.getHSBColor(rnd.nextFloat(), rnd.nextFloat()/3 + .66f,	rnd.nextFloat()/3 + .66f);
		else
			ballColor = Color.cyan;
	}
	public void hitThePaddle(double d, double l){
		this.hitThePaddle( d/l );
	}
	public void hitThePaddle(double rat){ //ratio is a double between 0.0 and 1.0
		ratio = rat;
		double theta = 30 + ratio * (150 - 30);
		double r = Math.hypot(vx, vy);
		vx = r * Math.cos(Math.toRadians(theta));
		vy = -r * Math.sin(Math.toRadians(theta));
	}
	public void resetFrame(){
		width = rad*2;
		thisBall.setFrame(x - rad, y - rad, 2*rad, 2*rad);
	}
	public void stickTo(Paddle paddle){
		x = paddle.x - dx; //where dx = paddle.x - ball.x
		resetFrame();
	}
	
	@Override
	public void move(){
		if (((collidingLeftEdge() && vx < 0) || (collidingRightEdge() && vx > 0)) && !stuck){
			vx = -vx;
			changeColor();
		}
		else if(y - rad < 0 && vy < 0){ //top edge ricochet
			if(!power){
				vy = -vy;
				changeColor();
			}
		}
	
		if(Math.abs(vy) < Math.hypot(3,3)/2){ //prevent the ball's angle from being too shallow
			double r = Math.hypot(vx, vy);
			vy = Math.signum(vy) * r / 2;
			vx = Math.signum(vx) * Math.sqrt(r*r - vy*vy);
		}
			
		if(!stuck){ //only move if not stuck
			x += vx * speed;
			y += vy * speed;
		}
		resetFrame();
	}
	
	@Override
	public void paint(Graphics2D g){
		if(power){
			g.setColor(Color.black);
			g.setFont(new Font("Courier", Font.BOLD, 30));
			g.drawString("PB", (int)(x - rad*.8), (int)(y + rad*.4));
		}
		if(stuck){
			g.setColor(Color.white);
			g.draw(new Line2D.Double(x, y, x + (vx*speed * 10), y + (vy*speed * 10)));
		}
		
		g.setColor(ballColor);
		g.fill(thisBall);
	}
}

class Brick{
	double x, y;
	double height = 40, width;
	int screenW = GUI.screenWidth;
	int screenH = GUI.screenHeight;
	int type, row, col;
	int margin;
	Rectangle2D thisBrick;
	Color brickColor = Color.cyan;
	boolean exposedSide[] = {false, false, false, false}; //0 = top, 1 = right, 2 = bottom, 3 = left
	boolean active = true, locked = false;
	
	public Brick(int type, double width, int row, int col, int margin){
		this.type = type;
		this.width = width;
		this.row = row;
		this.col = col;
		this.margin = margin;
		x = width*col + margin*(col + 1);
		y = height*row + margin*(row + 1);
		thisBrick = new Rectangle2D.Double(x, y, width, height);
		if(row == 0)
			exposedSide[0] = true;		
		if(type == 0) //when we initialize bricks, bottom row has exposed bottom sides
			exposedSide[2] = true;
		if(col == 0) //leftmost bricks have exposed left sides
			exposedSide[3] = true;
		if(x + width >= screenW - width/2) //rightmost bricks have exposed right sides (technically)
			exposedSide[1] = true;
	}
	
	public void deactivate(){
		active = false;
		
		if(exposedSide[0]){ //top
			y -= margin;
			height += margin;
		}
		if(exposedSide[2]) //bottom
			height += margin*2;
		if(exposedSide[3]){ //left
			x -= margin;
			width += margin;
		}	
		if(exposedSide[1]) //right
			width += margin*2;
		
		thisBrick.setFrame(x, y, width, height);
	}
	public boolean isExposed(){
		for(int i = 0; i < exposedSide.length; i++){
			if(exposedSide[i] == true)
				return true;
		}
		return false;
	}
	public void setColor(Color c){ brickColor = c; }
	

	public void paint(Graphics2D g){
 		g.setColor(brickColor);
		g.fill(thisBrick);		
		
		// g.setColor(Color.black);
// 		g.setFont(new Font("American Typewriter", Font.PLAIN, 15));
// 		g.drawString("" + type, (float)(x ), (float)(y + height/2));//draw rows and cols
		//g.drawString("(" + row + ", " + col + ")", (float)(x ), (float)(y + height/2));//draw rows and cols
	}
}

class PowerUp extends Paddle{
	Rectangle2D pupRect;
	double vy, timer = 0;
	boolean active = false, stored = false, showHint = false;
	Type type;
	Color powerColor;
	String initials = "";
	
	public enum Type{
		BIG_BALL, BIG_PADDLE, MULT_BALLS, STICKY_PADDLE, POWER_BALL, EXTRA_LIFE;
		
		public static Type randomType(){
			Random rnd = new Random();
			int randType = rnd.nextInt(100) + 1;
			
			if( randType <= 20 ) //20% 
				return BIG_BALL;
			else if( randType <= 40 ) //20%
				return BIG_PADDLE;
			else if( randType <= 55 ) //15%
				return MULT_BALLS;
			else if( randType <= 70) //15%
				return EXTRA_LIFE;
			else if( randType <= 90 ) //20%
				return STICKY_PADDLE;
			else  //10%
				return POWER_BALL;
			//return values()[new Random().nextInt(values().length)];
		}
		
		
	}

	public PowerUp (double xC, double yC, Type t){
		super();
		x = xC;
		y = yC;
		vx = 0;
		vy = 1;
		type = t;
		height = width = 40;
		this.setColor();
		pupRect = new Rectangle2D.Double(x - width/2, y - height/2, width, height);
	}

	public void setColor(){
		switch(type){
			case BIG_BALL:
				powerColor = Color.green;
				initials = "BB";
				break;
			case BIG_PADDLE:
				powerColor = Color.red;
				initials = "BP";
				break;
			case MULT_BALLS:
				powerColor = Color.yellow;
				initials = "MB";
				break;
			case EXTRA_LIFE:
				powerColor = new Color(0f, .75f, .0f);
				initials = "+L";
				break;
			case STICKY_PADDLE:
				powerColor = Color.magenta;
				initials = "SP";
				break;
			case POWER_BALL:
				powerColor = Color.cyan;
				initials = "PB";
				break;
		}
	}
	public String typeToString(String action){
		switch(type){
			case BIG_BALL:
				return "Big Ball: Activate to double the Ball's size for 15s";
			case BIG_PADDLE:
				return "Big Paddle: Activate to double the Paddle's size for 15s";
			case MULT_BALLS:
				return "Multi-Ball: Activate to spawn 3 extra balls at once";
			case EXTRA_LIFE:
				return "Extra Life: Activate to give yourself an extra life";
			case STICKY_PADDLE:
				return "Sticky Paddle: Release stuck balls with " + action + " for 15s";
			case POWER_BALL:
				return "Power Ball: Activate to launch a Power Ball that destroys everything in it's path";
			default:
				return "PowerUp Not Found";
		}
	}

	public void setRect(double x, double y){
		this.x = x;
		this.y = y;
		pupRect.setFrame(x - width/2, y - height/2, width, height);
	}
	public void setRect(PowerUp pup){
		x = pup.x;
		y = pup.y;
		pupRect.setFrame(x - width/2, y - height/2, width, height);
	}

	@Override
	public void move(){
			y += vy * speed;
			pupRect.setFrame(x - width/2, y - height/2, width, height);
	}
	
	public void activate(){
		active = true;
		stored = false;
		setRect(screenW/2 - width/2 + 30, screenH - 80 - height/3);
	}
	
	public String toString(){
		return "PowerUp - Type: " + type + " - " + pupRect.toString().split("Double")[1];
	}
	
	@Override
	public void paint(Graphics2D g){
		if(!active){ //we will draw active pups separately
			g.setColor(powerColor);
			g.fill(pupRect);
			g.setColor(Color.black);
			g.setFont(new Font("Courier", Font.BOLD, 30));
			g.drawString(initials, (int)(x - width/2 + 2), (int)(y + height*.2));
		}
	}

}

class Settings extends JTabbedPane implements ChangeListener, ActionListener{
	JFrame frame = new JFrame("Settings Window");
	JPanel tab1 = new JPanel();
	JPanel tab2 = new JPanel();
	double x, y, width, height;
	Rectangle2D icon;
	GUI gui;
	JLabel colorLabel;
	JSlider colorSlider, speedSlider;
	JButton autoMove, cheats, locked;
	JTable table;
	Rectangle colorTrack, speedTrack;
	BufferedImage background;
	Settings thisSetting;
	Color paddleColor;
	boolean wrongInput = false, dontAsk = false;
	
	
	public Settings(GUI g){
		gui = g;
		width = height = 50;
		x = g.screenWidth - width - 5;
		y = g.screenHeight - 80 - height - 5;
		icon = new Rectangle2D.Double(x, y, width, height);
		
		thisSetting = this;
		tab1.setOpaque(false);
		tab1.setLayout(new BoxLayout(tab1, BoxLayout.PAGE_AXIS));
		//setBackground(Color.black);
		try{ background = ImageIO.read(SplashScreen.class.getResourceAsStream("images/BG5.jpg")); }
		catch(IOException e){ System.out.println("File Not Found"); }
		initializeSettings();
		setUpDrawables();
		
	}

	public void initializeSettings(){
		setPreferredSize(new Dimension(450, 600));
				
		setUpColorSettings();
		setUpSpeedSettings();
		setUpToggles();
		setUpControlList();
		
		this.addTab("General", tab1);
		//this.addTab("Controls", tab2);
		
		frame.setContentPane(this);
		frame.pack();
		frame.setFocusTraversalKeysEnabled(false);
		frame.setResizable(false);
		// frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
// 		frame.setVisible(true);
	}
	
	public void setUpColorSettings(){
		paddleColor = gui.paddle.paddleColor;
		float hsbvals[] = Color.RGBtoHSB(paddleColor.getRed(), paddleColor.getGreen(), paddleColor.getBlue(), null);
		colorSlider = new JSlider(0, 1000, (int)(hsbvals[0] * 1000)); //hues 0 to 1000 with initial hue being paddleColor
		colorSlider.setPreferredSize(new Dimension((int)(tab1.getPreferredSize().width * .8), colorSlider.getPreferredSize().height));
		colorSlider.addChangeListener(this);
		colorSlider.setOpaque(false);
		colorSlider.setBorder(BorderFactory.createEmptyBorder(0,0,0,5));
		

		colorLabel = new JLabel("" + colorSlider.getValue()/10);
		colorLabel.setForeground(Color.white);
		colorLabel.setBorder(BorderFactory.createEmptyBorder(0,5,0,0));
		
		JPanel group = new JPanel(){
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
            public Dimension getPreferredSize() {
                return new Dimension(thisSetting.getPreferredSize().width,
                                     colorSlider.getPreferredSize().height * 2);
            }
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }
        };
		group.setOpaque(false);
		group.setLayout(new BoxLayout(group, BoxLayout.LINE_AXIS));
		TitledBorder bdr = BorderFactory.createTitledBorder("Paddle Color");
		bdr.setTitleColor(Color.white);
		group.setBorder(BorderFactory.createCompoundBorder(bdr, BorderFactory.createEmptyBorder(5,5,5,5)));
  		
        
		group.add(colorSlider);
		group.add(colorLabel);
		group.add(Box.createRigidArea(new Dimension(10, 10)));
		
		tab1.add(group);
	}
	public void setUpSpeedSettings(){
		speedSlider = new JSlider(35, 100, (int)(Math.abs(gui.paddleVX) * 10));
		speedSlider.addChangeListener(this);
		speedSlider.setOpaque(false);			
		speedSlider.setMajorTickSpacing(5);
		speedSlider.setMinorTickSpacing(5);
		speedSlider.setSnapToTicks(true);
		speedSlider.setPaintTicks(true);
		speedSlider.setPaintLabels(true);
		
		Hashtable<Integer, JLabel> labels = new Hashtable<Integer, JLabel>();
		for(int i : Arrays.asList(35, 50, 75, 95)){
			JLabel label = new JLabel();
			label.setForeground(Color.white);
			switch(i){
				case 35:
					label.setText("Low");
					labels.put(i, label);
				break;
				case 50:
					label.setText("Med");
					labels.put(i, label);
				break;
				case 75:
					label.setText("High");
					labels.put(i, label);
				break;
				case 95:
					label.setText("Very High");
					labels.put(i, label);
				break;
			}
		}
		speedSlider.setLabelTable(labels);
	
		JPanel group = new JPanel(){
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
            public Dimension getPreferredSize() {
                return new Dimension(thisSetting.getPreferredSize().width,
                                     speedSlider.getPreferredSize().height * 15 / 10);
            }
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }
        };
		group.setOpaque(false);
		group.setLayout(new BoxLayout(group, BoxLayout.LINE_AXIS));
		TitledBorder bdr = BorderFactory.createTitledBorder("Difficulty/Speed");
		bdr.setTitleColor(Color.white);
		group.setBorder(BorderFactory.createCompoundBorder(bdr, BorderFactory.createEmptyBorder(5,5,5,5)));
		group.add(speedSlider);
		tab1.add(group);
	}
	public void setUpToggles(){
		autoMove = new JButton("Paddle Auto-Move");
		cheats = new JButton("Cheats");
		locked = new JButton("Locked Bricks");
	
		autoMove.setSelected(gui.getAutoMove());
		cheats.setSelected(gui.cheatsActive);
		locked.setSelected(gui.lockedEnabled);
		
		if(autoMove.isSelected()){ autoMove.setForeground(Color.white); }
		if(cheats.isSelected()){ cheats.setForeground(Color.white); }
		if(locked.isSelected()){ locked.setForeground(Color.white); }
		
		autoMove.addActionListener(this);
		autoMove.setActionCommand("auto");
		cheats.addActionListener(this);
		cheats.setActionCommand("cheat");
		locked.addActionListener(this);
		locked.setActionCommand("lock");
		
		JPanel group = new JPanel(){
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
            public Dimension getPreferredSize() {
                return new Dimension(thisSetting.getPreferredSize().width, colorSlider.getPreferredSize().height * 2);
            }
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }
        };
		group.setOpaque(false);
		group.setLayout(new BoxLayout(group, BoxLayout.LINE_AXIS));
		TitledBorder bdr = BorderFactory.createTitledBorder("Toggleable Settings");
		bdr.setTitleColor(Color.white);
		group.setBorder(BorderFactory.createCompoundBorder(bdr, BorderFactory.createEmptyBorder(5,5,5,5)));
		group.add(autoMove);
		group.add(Box.createHorizontalGlue());
		group.add(cheats);
		group.add(Box.createHorizontalGlue());
		group.add(locked);
		tab1.add(group);
	}	
	public void setUpControlList(){
		if(table != null) //update for the cheat toggle
			tab1.remove(3);
		
		//Create Table
		InputMap inputs = gui.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW);
		ArrayList<KeyStroke> keys = new ArrayList<KeyStroke>(Arrays.asList(inputs.keys())); //get list of keys in GUI's inputmap
		keys.sort((k1, k2) -> ((String)inputs.get(k1)).compareTo((String)inputs.get(k2))); //sort keystrokes by their actions
		keys.removeIf(key -> key.isOnKeyRelease() == true); //remove "released" keystrokes
		
		Object[][] rowdata = new Object[keys.size()][2];
		for(int i = 0; i < keys.size(); i++){
			KeyStroke key = keys.get(i);
			rowdata[i][0] = key;
			rowdata[i][1] = inputs.get(key);
		} //populate the table with keys and their actions
		Object colNames[] = {"Key", "Action"};
		table = new JTable(rowdata, colNames){
			@Override
			public boolean isCellEditable(int row, int col){ //no cells are editable
				return false;
			}
			@Override
			public void setValueAt(Object value, int row, int col) {
				KeyStroke newKey = (KeyStroke) value;
				KeyStroke oldKey = (KeyStroke) getValueAt(row, col);
				for(int i = 0; i < this.getRowCount(); i++){ //don't change value if it exists already
					if(getValueAt(i, col).equals(value)){
						wrongInput = true;
						updateUI();
						return;
					}
				}
				wrongInput = false; //change border color to default when making a correct choice
				updateUI();
				
				if(gui.updateKeyBind(oldKey, newKey)) //update keybind. Only if successful, change table
					getModel().setValueAt(value, row, col);
				
			}
		};
		table.setOpaque(false); //set transparency
		table.setShowGrid(false);
		table.setRowHeight( table.getRowHeight() + 1 );
		table.setFont(new Font("Arial", Font.PLAIN, 15));
		table.setForeground(Color.white); //set text color
		table.setSelectionForeground(Color.white);
		
		table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer(){
			public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus,int row,int col) {
				Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, col);
				
				KeyStroke key = (KeyStroke)(t.getModel().getValueAt(row, col));
				String keyName = KeyEvent.getKeyText(key.getKeyCode()); //change our cells to display KeyText
				if (keyName.equals("␣")) { keyName = "Space"; }
				if (keyName.equals("⇥")) { keyName = "Tab"; }
				if (keyName.equals("⇪")) { keyName = "CAPS"; }
				
				if(key.getModifiers() == 195){
					setForeground(Color.red);
					setToolTipText("Inactive Key Binding. Update required.");
				}
				else
					setForeground(Color.white);
				
				if(isSelected && (getBorder() instanceof javax.swing.border.EmptyBorder) == false && wrongInput){
					setBorder(new BorderUIResource.LineBorderUIResource(Color.red)); //set Border to red if they try to input existing keybind
					setToolTipText("That Key Binding already exists!");
				}else if(getForeground().equals(Color.white))
					setToolTipText("Select this cell and press a key to update this Key Binding");	
				
				setOpaque(false);
				setText(keyName); //Display the KeyText
				setHorizontalAlignment(SwingConstants.CENTER); //Center Align the Keys
				return c;
			}
		});
		table.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer(){
			public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus,int row,int col) {
				Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, col);
				
				KeyStroke key = (KeyStroke)(t.getModel().getValueAt(row, 0));
				String action = (String)(t.getModel().getValueAt(row, 1));
				
				switch(action){
					case "Action":
						setToolTipText("Actions include: Unpausing the game and launching balls from Sticky Paddles");
						break;
					case "Paddle Speed ↑ / Ball Angle":
						setToolTipText("Increase Paddle Speed / Adjust Launch Angle for stuck balls");
						break;
					case "Paddle Speed ↓ / Ball Angle":
						setToolTipText("Decrease Paddle Speed / Adjust Launch Angle for stuck balls");
						break;
					case "Spawn Balls":
						setToolTipText("Launch a new ball from the center of the paddle - *CAN CAUSE ERRORS*");
						break;
					case "Spawn PowerUp":
						setToolTipText("Spawn a Random PowerUp in the Center of the Screen");
						break;
					case "Show Details":
						setToolTipText("Display Frames per Second, Game Timer, and Mouse Position");
						break;
					default:
						setToolTipText(action);
						break;
				}
				
				if(key.getModifiers() == 195){
					setForeground(Color.red);
					setToolTipText("Inactive Key Binding. Update required.");
				}
				else
					setForeground(Color.white);
				
				if(table.isCellSelected(row, 0) && wrongInput){
					setBorder(new BorderUIResource.LineBorderUIResource(Color.red)); //set Border to red if they try to input existing keybind
					setToolTipText("That Key Binding already exists!");
				}
				
				setOpaque(false);
				setHorizontalAlignment(SwingConstants.LEFT); //Left Align the Actions
				return c;
			}
		});
		table.getColumnModel().getColumn(0).setMaxWidth(80); //force key column width
 		table.getColumnModel().getColumn(1).setMinWidth(230);//force Action col width
		
		table.getColumnModel().setSelectionModel(new DefaultListSelectionModel() {
			private boolean isSelectable(int row, int col) {
				if(col == 1)
					return false;
				return true;
			}

			@Override
			public void setSelectionInterval(int index0, int index1) {
				if(isSelectable(index0, index1)) {
					super.setSelectionInterval(index0, index1);
				}
				wrongInput = false; //change border color to default when clicking elsewhere
				updateUI();
			}

			@Override
			public void addSelectionInterval(int index0, int index1) {
				if(isSelectable(index0, index1)) {
					super.addSelectionInterval(index0, index1);
				}
			}
		}); //prevent Action selection
		
		JTableHeader header = table.getTableHeader();
		header.setResizingAllowed(false);
		header.setReorderingAllowed(false);
		((DefaultTableCellRenderer)header.getDefaultRenderer()).setHorizontalAlignment(SwingConstants.CENTER);
		header.setOpaque(false); //set transparency
		header.setBackground(new Color(1.0f, 1.0f, 1.0f, 0.1f));
		header.setFont(new Font("Arial", Font.BOLD, 20));
		header.setForeground(new Color(255, 209, 0)); //set text color to Gold
		
		handleTableIO();
		
		JPanel group = new JPanel();
		group.setOpaque(false);
		TitledBorder bdr = BorderFactory.createTitledBorder("Control List - Hover For More Info");
		bdr.setTitleColor(Color.white);
		group.setBorder(BorderFactory.createCompoundBorder(bdr, BorderFactory.createEmptyBorder(5,5,5,5)));
		group.add(header);
		group.add(table, BorderLayout.CENTER);
		
		tab1.add(group);
	}

	public void setUpDrawables(){
		colorTrack = SwingUtilities.convertRectangle(colorSlider, colorSlider.getBounds(), tab1);
		colorTrack.setRect(colorTrack.getX() * 1.65, colorTrack.getY() + colorTrack.getHeight() - 4, colorTrack.getWidth() * .907, 3);
		speedTrack = SwingUtilities.convertRectangle(speedSlider, speedSlider.getBounds(), tab1);
  		speedTrack.setRect(speedTrack.getX() * 2.5, speedTrack.getY() + speedTrack.getHeight()/2 - 3, speedTrack.getWidth() * .84, 2);
	}
	public void handleTableIO(){
		table.addKeyListener(new KeyAdapter(){
			public void keyPressed(KeyEvent e){
				if(table.getSelectedRow() != -1)
					table.setValueAt(KeyStroke.getKeyStrokeForEvent(e), table.getSelectedRow(), 0);
			}
		});
		
	}
	public int confirmCheatToggle(){
		JLabel msg = new JLabel("Are you sure you want to Enable Cheats?");
		JLabel msg2 = new JLabel("Enabling Cheats disqualifies you from the High Scores");
		JLabel msg3 = new JLabel("(Enabling Cheats can potentially cause Brickout to crash)");
		JCheckBox dma = new JCheckBox("Do not ask me again");
		msg3.setFont(msg3.getFont().deriveFont(Font.ITALIC));
		JPanel msgPanel = new JPanel();
		msgPanel.setLayout(new BoxLayout(msgPanel, BoxLayout.PAGE_AXIS));
		msgPanel.add(Box.createRigidArea(new Dimension(0, 15)));
		msgPanel.add(msg);
		msgPanel.add(msg2);
		msgPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		msgPanel.add(msg3);
		msgPanel.add(Box.createRigidArea(new Dimension(0, 25)));
		msgPanel.add(dma);
		int confirm = JOptionPane.showConfirmDialog(this, 
				msgPanel, "Cheats Confirmation", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if(dma.isSelected())
			dontAsk = true;
		
		return confirm;
	}
	
	public void paintComponent(Graphics gg){
		Graphics2D g = (Graphics2D) gg;
		g.drawImage(background, 0, 0, getPreferredSize().width, getPreferredSize().height, this);
		
		if(colorTrack != null){
			g.setColor(paddleColor);
	 		g.fill(colorTrack);
	 	}else { System.out.println("colorTrack is null"); }
	 	if(speedTrack != null){
			g.setColor(Color.white);
			g.fill(speedTrack);
		}else { System.out.println("speedTrack is null"); }	
 		super.paintComponent(gg);
	}
	
	@Override
	public void stateChanged(ChangeEvent e){
		JSlider source = (JSlider) e.getSource();
		
		if(source.equals(colorSlider)){ //change colorSlider		
			float value = source.getValue() / 1000.0f; //gets percentage
			colorLabel.setText("" + source.getValue() /10);
			paddleColor = Color.getHSBColor(value, 1.0f, 1.0f);
			gui.paddle.paddleColor = paddleColor;
			repaint();
		}
		else if(source.equals(speedSlider)){ //change speedSlider
			double value = (double)source.getValue();
			gui.setDifficulty(value);
		}
	}
	
	@Override
	public void actionPerformed(ActionEvent e){
		if("auto".equals(e.getActionCommand())){
			autoMove.setSelected(!autoMove.isSelected());
			gui.setAutoMove(autoMove.isSelected());
			if(autoMove.isSelected())
				autoMove.setForeground(Color.white);
			else
				autoMove.setForeground(Color.black);
		}
		else if("cheat".equals(e.getActionCommand()) && !gui.gameOver){
			int cheatConfirmation = 0;
			if(cheats.isSelected() == false && dontAsk == false)
				cheatConfirmation = confirmCheatToggle();
			
			if(cheatConfirmation == 0){
				cheats.setSelected(!cheats.isSelected());
				gui.toggleCheatBindings(!gui.cheatsActive);
				setUpControlList();
				if(cheats.isSelected()) { gui.hsEligible = false; } //ineligible for high scores if cheats active
			}
				
			if(cheats.isSelected())
				cheats.setForeground(Color.white);
			else
				cheats.setForeground(Color.black);
		}
		else if("lock".equals(e.getActionCommand())){
			locked.setSelected(!locked.isSelected());
			gui.setLocked(locked.isSelected());	
			if(locked.isSelected())
				locked.setForeground(Color.white);
			else
				locked.setForeground(Color.black);	
		}
	
	}
}

class SplashScreen extends JPanel implements MouseListener{
	boolean showing = true;
	int screenWidth, screenHeight;
	double gameScale = .75;
	BufferedImage buffer, background;
	Graphics2D bg;
	Font gameFont = new Font("Apple Chancery", Font.PLAIN, 40);

	public SplashScreen(int sw, int sh){
		addMouseListener(this);
		screenWidth = sw;
		screenHeight = sh;
		buffer = new BufferedImage(screenWidth * 2, screenHeight * 2, BufferedImage.TYPE_INT_RGB);
		bg = buffer.createGraphics();
		bg.scale(gameScale, gameScale);
		bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		try{ background = ImageIO.read(SplashScreen.class.getResourceAsStream("images/BG5.jpg")); }
		catch(IOException e){ System.out.println("File Not Found"); }
		setPreferredSize(new Dimension((int)(screenWidth * gameScale), (int)(screenHeight * gameScale)));		
		addComponentListener(new ComponentAdapter(){
			@Override
			public void componentResized(ComponentEvent e){
				Rectangle b = e.getComponent().getBounds();
				bg.scale(1/gameScale, 1/gameScale);
				gameScale = b.width/(double)screenWidth;
 				bg.clearRect(0,0,getWidth(), getHeight());
				bg.scale(gameScale, gameScale);
			}
		});
	}

	@Override
	public void paintComponent(Graphics gg){
		bg.drawImage(background, 0, 0, screenWidth, screenHeight, this);
		bg.setColor(new Color(255, 209, 0)); //gold

		int cx1 = getCenterForMaxFont(500, "Welcome to Brickout!", 100);
		bg.drawString("Welcome to Brickout!", cx1, screenHeight/3 + 50);
		bg.setFont(new Font("Courier", Font.PLAIN, 40));
		drawCentered("By: Marco Hernandez",screenHeight/3 + 130);
		drawCentered("Move with LEFT and RIGHT Arrows", screenHeight/3 + 230);
		drawCentered("Or with A and D Keys", screenHeight/3 + 270);
		drawCentered("Press P to Pause", screenHeight/3 + 330);
		
		bg.setColor(Color.white);
		int cx2 = getCenterForMaxFont(500, "Click Anywhere to Start!", 75);
		bg.drawString("Click Anywhere to Start!", cx2, screenHeight - getFontSize(bg));
		
		Graphics2D g = (Graphics2D) gg;
		g.drawImage(buffer, null, 0, 0);
	
	}
	
	public boolean showing() { return showing; }
	
	public void drawCentered(String str, int y){
		int centerX = (screenWidth - bg.getFontMetrics().stringWidth(str))/2;
		bg.drawString(str, centerX, y);
	}
	public int getCenterForMaxFont(int height, String str, int fontsize){
		bg.setFont(gameFont.deriveFont((float)fontsize));
		FontMetrics metrics = bg.getFontMetrics();
		int centerX = (screenWidth - metrics.stringWidth(str)) / 2;
		
		if(screenWidth < metrics.stringWidth(str) || metrics.getHeight() > height){
			centerX = getCenterForMaxFont(height, str, fontsize - 1);
		}
		else if(screenWidth - metrics.stringWidth(str) > screenWidth * .05){
			centerX = getCenterForMaxFont(height, str, fontsize + 1);
		}
		
		return centerX;
	}
	public int getFontSize(Graphics2D b){
		String mets = b.getFontMetrics().toString();
		mets = mets.substring(mets.indexOf("size"), mets.indexOf("]"));
		
		return Integer.parseInt(mets.substring(5));
	}
	
	public void mouseClicked(MouseEvent e) {
		showing = false;
	}	
	public void mousePressed(MouseEvent e) {}
	public void mouseReleased(MouseEvent e) {}
	public void mouseEntered(MouseEvent e) {}
	public void mouseExited(MouseEvent e) {}
}