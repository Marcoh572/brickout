import java.awt.image.BufferedImage;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import javax.swing.*;
import javax.swing.event.*;
import java.beans.*;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class brickout {
	static final int FPS = 50;
	static final int timeStep = (int)1000/FPS;
	static final int ScreenWidth = 1000, ScreenHeight = 1000;
	
	public static void main(String[] args){	
		boolean showSplash = true;
		JFrame frame = new JFrame("BrickOut - By: Marco");
		GUI gui = new GUI(ScreenWidth, ScreenHeight);
		frame.addComponentListener(new ComponentAdapter(){
			@Override
			public void componentResized(ComponentEvent e){						
				frame.setSize(frame.getWidth(), frame.getWidth() + 22);
			}
		});

		SplashScreen splash = new SplashScreen(ScreenWidth, ScreenHeight);
		frame.add(splash);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.pack();
		frame.setVisible(true);

		
		while (true){	
			try{
				if(showSplash){ //Splash Loop
					if(!splash.showing()){
						frame.remove(splash);
						frame.add(gui, BorderLayout.CENTER);
						frame.pack();
						showSplash = false;
					}
					splash.repaint();
				}
				else{ //Game Loop
					
					if(gui.replay){
						frame.remove(gui);
						gui = new GUI(ScreenWidth, ScreenHeight);
						frame.add(gui, BorderLayout.CENTER);
						frame.pack();
					}
					
					while(gui.gameOver == false){
						try{
							gui.move();
							gui.repaint();
							Thread.sleep(timeStep); 

						} catch (InterruptedException e) { }
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
	int level = 1, score = 0, lives = 3, storedPups = 0, ballMultiplier = 3, timeSinceLastBuff = 0;
	Point mousePos = null;
	BufferedImage buffer, background;
	Graphics2D bg;
	double ballsize = 30, brickheight, gameScale =  .75, fpsTimer, startTime, gameTimer = 0, buffTimer = 0;
	boolean paused = true, gameOver = false, replay = false, showFPS = true, cheatsActive = true;
	Rectangle2D bottomBar, intersection;
	Paddle paddle;
	Settings settings;
	ArrayList<Ball> balls = new ArrayList<Ball>();
	ArrayList<ArrayList<Brick>> bricks = new ArrayList<ArrayList<Brick>>();
	ArrayList<Brick> exposedBricks = new ArrayList<Brick>();
	ArrayList<PowerUp> pups = new ArrayList<PowerUp>();
	ArrayList<String> inputList = new ArrayList<String>();
	PowerUp activePup;
	Color colors[] = { new Color(220, 0, 0), new Color(250, 150, 0), new Color(255, 209, 0), new Color(0, 220, 0), new Color(0, 220, 200), new Color(0, 0, 220), new Color(150, 0, 200) };
	Font gameFont = new Font("Courier", Font.PLAIN, 32);
	Random roll = new Random();
	
	
	public GUI(int sW, int sH){
		addMouseListener(this);
		addMouseMotionListener(this);
		screenWidth = sW;
		screenHeight = sH;
		
		buffer = new BufferedImage(screenWidth * 2, screenHeight * 2, BufferedImage.TYPE_INT_RGB);
		bg = buffer.createGraphics();
		bg.scale(gameScale, gameScale);
		bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		
		bottomBar = new Rectangle2D.Double(0, screenHeight - 80, screenWidth, 80);
		paddle = new Paddle();
		balls.add(new Ball( ballsize/2 ));
		brickheight = this.populateBricks();
		try{ background = ImageIO.read(GUI.class.getResourceAsStream("images/BG1.jpg")); }
		catch(IOException e){ System.out.println("File Not Found"); }

		this.initializeKeyBindings();
		this.initializeSettings();
		setPreferredSize(new Dimension((int)(screenWidth * gameScale), (int)(screenHeight * gameScale)));		
		addComponentListener(new ComponentAdapter(){
			@Override
			public void componentResized(ComponentEvent e){
				Rectangle b = e.getComponent().getBounds();
				bg.scale(1/gameScale, 1/gameScale);
				gameScale = b.width/(double)screenWidth;
 				bg.clearRect(0,0,getWidth(), getHeight());
				bg.scale(gameScale, gameScale);
				//System.out.println(gameScale);
			}
		});
		
		settings = new Settings(this);
		fpsTimer = System.currentTimeMillis();
		startTime = fpsTimer;
	}

	public void move(){
		if (!paused && !gameOver){
			if(System.currentTimeMillis() - startTime >= 10)
				gameTimer++;
			
			timeSinceLastBuff++;
			if(timeSinceLastBuff > 5400 * 2){ //spawn a powerup after 2 minutes without one
				pups.add(new PowerUp(screenWidth/2, margin*(rows+1) + brickheight*rows + brickheight/2, PowerUp.Type.randomType()));
				timeSinceLastBuff = 0;
			}
			
			if(gameTimer >= buffTimer && activePup != null)
				deactivateBuff();
			
			paddle.move();
			balls.forEach( ball ->{
				ball.move();
				if(ball.stuck)
					ball.stickTo(paddle);
			});
			for(Ball ball : balls){
				if(ball.y - ball.rad >= bottomBar.getY() || ball.y + ballsize < 0){ //lose a life scenario
					loseALife(ball);
					return;
				}
				else if(paddle.thisPaddle.intersects(ball.thisBall.getBounds()) && ball.stuck == false){ //ball hits the paddle
					ball.hitThePaddle(paddle.width - (ball.x - paddle.thisPaddle.getX()), paddle.width);
					ball.changeColor();
					if(paddle.sticky){
						ball.stuck = true;
						paddle.hasBallStuck = true;
						ball.dx = paddle.x - ball.x;
						ball.y = paddle.thisPaddle.getY() - ball.rad;
					}
				}
				else if(ball.y - ball.rad <= (rows*brickheight) + margin*(rows+1)){
					for( Brick brick : exposedBricks ){
						if(brick.active && brick.thisBrick.intersects(ball.thisBall.getBounds())){
							intersection = brick.thisBrick.createIntersection(ball.thisBall.getBounds());
							
							//powerballs go through bricks
							if(intersection.getHeight() < intersection.getWidth() && !ball.power) //hit top or bottom
								ball.vy = -ball.vy;
							else if (intersection.getHeight() == intersection.getWidth() && !ball.power){
								ball.vy = -ball.vy;
								ball.vx = -ball.vx;
							}
							else if( !ball.power ) //hit left or right
								ball.vx = -ball.vx;
								
							if(brick.locked && brick.thisBrick.contains(ball.x, ball.y)){
								ball.y += brickheight;	
								ball.vy = -1 * Math.abs(ball.vy);	
							}
							
							ball.changeColor();
							
							brick.type--;
							
							if(ball.power){ //powerballs only
								brick.deactivate();
								removeExposedBrick(brick);
								score += 10;
							}
							else if(brick.type  == -1 && !brick.locked){
								brick.deactivate();
								removeExposedBrick(brick);
								rollBuff(brick);
								score += 10;
							}
							else if (!brick.locked)
								brick.setColor(colors[brick.type]);
							
							return;
						}
					}
				}			
			}
			
			if( pups.size() > storedPups ){ //got moving pups
				for( PowerUp pup : pups ){
					pup.move();
					if(pup.pupRect.intersects(paddle.thisPaddle) && storedPups < 3){
						pup.stored = true;
						pup.vy = 0;
						pup.setRect(screenWidth/2 + 100 + (storedPups * pup.width * 1.5), bottomBar.getY() + pup.height/2);
						storedPups++;
					}
				}
				pups.removeIf( pup -> (pup.y + pup.height/2 >= bottomBar.getY() && pup.vy != 0));
			}
			
		}
		else if(paused){
		}
	}

	@Override
	public void paintComponent(Graphics gg){
		frames++;
		if(System.currentTimeMillis() - fpsTimer >= 1000){
			fps = frames;
			frames = 0;
			fpsTimer = System.currentTimeMillis();
		}
	
		bg.setBackground(Color.white);
		bg.clearRect(0, 0, screenWidth, screenHeight);
		bg.drawImage(background, 0, 0, screenWidth, screenHeight, this);
		
		bg.setColor(Color.black);
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
		}
		
		paddle.paint(bg);
		if(activePup != null){
			bg.setColor(glowingColor(Color.yellow));
			bg.fill(new Rectangle2D.Double(activePup.pupRect.getX() - margin, activePup.pupRect.getY() - margin, 
						activePup.pupRect.getWidth() + margin*2, activePup.pupRect.getHeight() + margin*2));	
			bg.setColor(Color.white);
			bg.setFont(new Font("Courier", Font.BOLD, 30));
			bg.drawString("" + (int)(buffTimer - gameTimer)/100, (float)(activePup.x + activePup.width*.67), (float)(activePup.y + activePup.height*.2));
			
			if(mousePos != null && activePup.pupRect.contains(mousePos)){
				int centerX = getCenterForMaxFont((int)(bottomBar.getHeight()/2), activePup.typeToString(), getFontSize(bg));
				bg.setColor(colors[2]);
				bg.drawString(activePup.typeToString(), centerX, (float)(screenHeight - getFontSize(bg)/2));	
			}
		}
		for(int i = 1; i <= storedPups; i++){
			bg.setColor(Color.white);
			bg.setFont(new Font("Courier", Font.BOLD, 30));
			PowerUp p = getStoredPup(i);
			bg.drawString("" + i, (float)(p.x - p.width * .2), (float)(p.y - p.height * .667));
			bg.fill(new Rectangle2D.Double(p.pupRect.getX() - margin, p.pupRect.getY() - margin, 
						p.pupRect.getWidth() + margin*2, p.pupRect.getHeight() + margin*2));
						
			if(mousePos != null && p.pupRect.contains(mousePos)){
				bg.setColor(colors[2]);
				int centerX = getCenterForMaxFont((int)(bottomBar.getHeight()/2), p.typeToString(), getFontSize(bg));
				
				bg.drawString(p.typeToString(), centerX, (float)(screenHeight - 5));	
				
			}
		
		} //action bar number and border
		pups.forEach( pup -> pup.paint(bg) );
		bg.setColor(Color.black);
		bricks.forEach( bList -> bList.forEach( brick -> {
			if(brick.active){
				bg.setColor(Color.black);
				bg.fill(new Rectangle2D.Double(brick.x - margin, brick.y - margin, brick.width + margin*2, brick.height + margin*2));
				brick.paint(bg);
			}
		}));
		balls.forEach( ball -> ball.paint(bg) );
		
		if(paused){
			bg.setColor(Color.red);
			bg.setFont(gameFont.deriveFont(Font.BOLD, 120));
			drawCentered("PAUSED", screenHeight/2 - 120);
			bg.setFont(gameFont);
			drawCentered("(Press Spacebar)", screenHeight/2 - 60);
			bg.setFont(new Font("Apple Chancery", Font.ITALIC, 200));
			bg.drawString("Level " + level, screenWidth/2 - 300, (int)bottomBar.getY() - 200);
			
			//settings.paintComponent(bg);
			bg.setColor(Color.white);
			bg.fill(settings.icon);
			bg.setColor(Color.black);
			bg.setFont(new Font("Courier", Font.PLAIN, 60));
			bg.drawString("S", (float)(settings.x + (settings.width - bg.getFontMetrics().stringWidth("S"))/2), (float)( settings.y + settings.height - 10 ));
		}
		if(gameOver){
			//bg.clearRect(0,0,screenWidth,screenHeight);
			bg.setColor(Color.red);
			bg.drawImage(background, 0, 0, screenWidth, screenHeight, this);
			bg.setFont(gameFont.deriveFont(Font.BOLD, 120));
			drawCentered("GAME OVER", screenHeight/2 - 160);
			bg.setFont(gameFont);
			drawCentered("(Press R to Replay)", screenHeight/2 - 60);
			bg.setColor(Color.black);
			bg.fill(bottomBar);
			bg.setColor(colors[2]); //gold
			bg.drawString("Lives:", 10, (int)bottomBar.getY()  + getFontSize(bg));
			bg.drawString("PowerUps: " , screenWidth/2 - 100, (int)bottomBar.getY()  + getFontSize(bg));
			bg.drawString("Score: " + score, screenWidth - 250, (int)bottomBar.getY()  + getFontSize(bg));
		}
		
		if(showFPS){
			bg.setFont(gameFont);
			bg.setColor(Color.white);
			bg.drawString("FPS:" + fps, 10, (int)bottomBar.getY() - 5);
			bg.drawString("GT:" + getTime(), 160,(int)bottomBar.getY() - 5);
			
			bg.setFont(gameFont.deriveFont(20f));
			if(mousePos != null)
				bg.drawString("(" + (int)(mousePos.getX()*gameScale) + ", " + (int)(mousePos.getY()*gameScale) + ")", 
								5, (int)bottomBar.getY() - 30);
			
		}
		
		Graphics2D g = (Graphics2D) gg;
		g.drawImage(buffer, null, 0, 0);
	}

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
		inputs.put(KeyStroke.getKeyStroke("SPACE"), "Player Action");
		inputs.put(KeyStroke.getKeyStroke("X"), "Cancel PowerUp");
		inputs.put(KeyStroke.getKeyStroke("R"), "Replay");
		inputs.put(KeyStroke.getKeyStroke("F"), "Show Details");
		
		if(cheatsActive){
			inputs.put(KeyStroke.getKeyStroke("UP"), "Inc Paddle Speed / Ball Angle");
			inputs.put(KeyStroke.getKeyStroke("DOWN"), "Dec Paddle Speed / Ball Angle");
			inputs.put(KeyStroke.getKeyStroke("V"), "Toggle Paddle AutoMove");
			inputs.put(KeyStroke.getKeyStroke("B"), "Fire Balls");
			inputs.put(KeyStroke.getKeyStroke("M"), "Tester");
		}
				
		AbstractAction moveLeft = new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				if(paddle.x - paddle.width/2 - 5 < 0)
					paddle.vx = 0;
				else if (paddle.autoMove)
					paddle.vx = -1 * Math.abs(paddle.vx);
				else
					paddle.vx = -5;
				
			}
		};
		AbstractAction moveRight = new AbstractAction(){
			public void actionPerformed(ActionEvent e){	
				if(paddle.x + paddle.width/2 + 5> screenWidth)
					paddle.vx = 0;
				else if (paddle.autoMove)
					paddle.vx = Math.abs(paddle.vx);
				else
					paddle.vx = 5;
			}
		};		
		actions.put("Stop Paddle", new AbstractAction(){
			public void actionPerformed(ActionEvent e){			
				if(!paddle.autoMove)
					paddle.vx = 0;
				else if (paddle.collidingLeftEdge())
					paddle.vx = 5;
				else if (paddle.collidingRightEdge())
					paddle.vx = -5;
			}
		});	
		actions.put("Move Left", moveLeft);
		actions.put("Move Right", moveRight);
		
		actions.put("Pause", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				paused = !paused;
				startTime = System.currentTimeMillis();
			}
		});
		actions.put("Player Action", new AbstractAction(){
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
				if(activePup != null){
					deactivateBuff();
				}	
			}
		});
		actions.put("Replay", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				if(gameOver){
					replay = true;
				}	
			}
		});
		actions.put("Show Details", new AbstractAction(){
			public void actionPerformed(ActionEvent e){
				showFPS = !showFPS;
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
		
		if(cheatsActive){
			actions.put("Inc Paddle Speed / Ball Angle", new AbstractAction(){
				public void actionPerformed(ActionEvent e){
					if(paddle.hasBallStuck){
						for( Ball ball : balls ){
							if (ball.ratio < 1.0)
								ball.ratio += .1;
							else
								ball.ratio = 1.0;
								
							ball.hitThePaddle(ball.ratio);
						}
					}
					else if(Math.abs(paddle.vx) < 15)	
						paddle.vx = Math.signum(paddle.vx) * (Math.abs(paddle.vx) + .5);
				}
			});
			actions.put("Dec Paddle Speed / Ball Angle", new AbstractAction(){
				public void actionPerformed(ActionEvent e){
					if(paddle.hasBallStuck){
						for( Ball ball : balls ){
							if (ball.ratio > 0.0)
								ball.ratio -= .1;
							else
								ball.ratio = 0.0;
								
							ball.hitThePaddle(ball.ratio);
						}
					}
					else if(Math.abs(paddle.vx) > .5)
						paddle.vx = Math.signum(paddle.vx) * (Math.abs(paddle.vx) - .5);
				}
			});
			actions.put("Toggle Paddle AutoMove", new AbstractAction(){
				public void actionPerformed(ActionEvent e){			
					paddle.autoMove = !paddle.autoMove;
					if(paddle.autoMove)
						paddle.vx = -5;
					else
						paddle.vx = 0;
				}
			});	
			actions.put("Fire Balls", new AbstractAction(){
				public void actionPerformed(ActionEvent e){
					Ball newBall = new Ball(ballsize/2, paddle.x, paddle.y - paddle.height);
					newBall.vy = -Math.hypot(newBall.vx, newBall.vy);
					newBall.vx = 0;
					balls.add(newBall);
				}
			});
			actions.put("Tester", new AbstractAction(){
				public void actionPerformed(ActionEvent e){
					lives--;
					//changeLevel();
					//if(activePup == null){
						//PowerUp p = new PowerUp(screenWidth/2, screenHeight * .667, PowerUp.Type.randomType());
	// 					p.vy = 0;
						//pups.add(p);
	// 					storedPups++;
	// 					activateBuff(p);
				// 	}
// 					else
// 						changeLevel();
				}
			});		
		}
		
		
		//Add Input Map contents to Input List
		java.util.List<KeyStroke> keys = Arrays.asList(inputs.keys());
		inputList.clear();
		keys.sort(Comparator.comparing(KeyStroke::getKeyCode));
		keys.forEach( k -> {
			String keyName = KeyEvent.getKeyText(k.getKeyCode());
			if(keyName.equals("␣")) { keyName = "Space"; }
			if(k.isOnKeyRelease() == false )//don't show released keys
				inputList.add("" + keyName + "\t-\t" + inputs.get(k));
		});
		//inputList.forEach( in -> System.out.println(in));
		
		
		
		
	}
	public void initializeSettings(){
		
		
 		/*settings.add(new JCheckBoxMenuItem(new AbstractAction(){
			@Override
			public void actionPerformed(ActionEvent e){
				settings.remove(settings.getSubElements().length - 1);
				//settings.reshow = true;
			}
		}));
		
		
		JMenuItem controlList = new JMenuItem("Controls");
		controlList.setEnabled(false);
		settings.add(controlList);
		
		
		for(String in : inputList){
			JMenuItem control = new JMenuItem(in);
			control.setEnabled(false);
			settings.add(control);
		}*/ //settings as popup
		
		
		
	}
	
	
	//utility methods
	public String getTime(){
		int minutes = (int)(gameTimer/6000);
		int seconds = (int)(gameTimer % 6000) /100 ;
		int milliseconds = (int)(gameTimer % 100);
		
		return minutes + ":" + seconds + ":" + milliseconds;
	}
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
		else if(screenWidth - metrics.stringWidth(str) > screenWidth * .1){
			centerX = getCenterForMaxFont(height, str, fontsize + 1);
		}
		
		//System.out.println(getFontSize(bg));
		return centerX;
		
	}
	public int getFontSize(Graphics2D b){
		String mets = b.getFontMetrics().toString();
		mets = mets.substring(mets.indexOf("size"), mets.indexOf("]"));
		
		return Integer.parseInt(mets.substring(5));
	}
	
	//game methods
	public double populateBricks(){ //populate bricks array and return brick height
		bricks.clear();
		exposedBricks.clear();
		for(int i = 0; i < rows; i++){
			bricks.add(new ArrayList<Brick> ());
			int brickType = rows - i - 1;
			
			for(int j = 0; j < cols; j++){
				double w = (screenWidth - (cols + 1)*margin)/ (double)cols;
				Brick temp = new Brick(brickType, w, i, j, margin);
				temp.setColor(colors[brickType]);
				
				int rollLocked = roll.nextInt(50 - brickType*3);
				if(rollLocked == 0){ 
					temp.locked = true; 
					temp.setColor(Color.gray);
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
				paddle = new Paddle(paddle.autoMove);
			}
			else
				gameOver = true;
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
		paddle = new Paddle(paddle.autoMove);
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
		int index;
		if( i > 6 )
			index = 1;
		else
			index = i;
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
			buffTimer = gameTimer + 1500; //1 gameTimer = 1/100th of a second. so 1500 = 15 seconds
		}
		
		switch (pup.type){
			case BIG_BALL:
				balls.forEach(ball -> {
					ball.rad = ballsize;
					if(ball.y < screenHeight){ ball.y += ballsize; }
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
	public Color glowingColor(Color c){
		int alpha = (int)gameTimer % 255;
		
		if(alpha > 127)
			alpha = 255-alpha;
	
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha * 2);
	}
	
	//Event methods
	public void mouseClicked(MouseEvent e) {
		/*Ball b = balls.get(0);
		double mX = e.getX() * 1/gameScale, mY = e.getY() * 1/gameScale;
		double theta = Math.atan2(mY - b.y, mX - b.x);
		System.out.println(Math.toDegrees(theta));
 		double r = Math.hypot(b.vx, b.vy);
 		b.vx = r * Math.cos(theta);
 		b.vy = r * Math.sin(theta);


		balls.set(0, b);*/
	
		if(!paused && storedPups > 0){
			for(int i = 1; i <= storedPups; i++){
				PowerUp p = getStoredPup(i);
				
				if(p != null && p.pupRect.contains(e.getX() * 1/gameScale, e.getY() * 1/gameScale)){
					switch (i){
						case 1:
							if(storedPups == 3)
								getStoredPup(3).setRect(getStoredPup(2));
							if(storedPups >= 2)
								getStoredPup(2).setRect(getStoredPup(1));
						break;
						case 2:
							if(storedPups == 3)
								getStoredPup(3).setRect(getStoredPup(2));
						break;

						
						
					}		
					
					activateBuff(p);
					
					break;	
				}
			}
		}
		
		if(paused && settings.icon.contains(e.getX() * 1/gameScale, e.getY() * 1/gameScale)){
			settings.frame.setVisible(true);
			//settings.show(this);
			//settings.show(this, (getWidth() - settings.getPreferredSize().width)/2, (getHeight() - settings.getPreferredSize().height)/2);
		}
		
		
	}	
	public void mousePressed(MouseEvent e) {}
	public void mouseReleased(MouseEvent e) {}
	public void mouseEntered(MouseEvent e) {}
	public void mouseExited(MouseEvent e) {
		mousePos = null;
	}
	public void mouseDragged(MouseEvent e){
		balls.get(0).x = e.getX() * 1/gameScale;
		balls.get(0).y = e.getY() * 1/gameScale;
	}
	public void mouseMoved(MouseEvent e){
		mousePos = e.getPoint();
		mousePos.setLocation(e.getX() * 1/gameScale, e.getY() * 1/gameScale);
		
	}
}

class Paddle{
	Rectangle2D thisPaddle;
	double x, y, vx = -5.0;
	double width = 150, height = 30; 
	double speed = 130/(double)brickout.FPS;
	int screenW, screenH;
	Color paddleColor = Color.blue;
	boolean autoMove = true, sticky = false, hasBallStuck = false;
	
	public Paddle(){
		this(GUI.screenWidth, GUI.screenHeight);
	}
	
	public Paddle(boolean auto){
		this();
		autoMove = auto;
		if(autoMove == false)
			vx = 0;
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
			if(autoMove)
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
	
	public boolean collidingRightEdge(){
		return (x + (width/2)) > screenW;
	}
	public boolean collidingLeftEdge(){
		return (x - (width/2)) < 0;
	}

	public void paint(Graphics2D g){
		g.setColor(paddleColor);
		g.fill(thisPaddle);
		g.setFont(new Font("American Typewriter", Font.BOLD, 15));
		g.setColor(Color.white);
		g.drawString(""+vx, (float)x - 15, (float)y);

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
		else if(y - rad < 0 && vy < 0){ //top edge
			if(!power){
				vy = -vy;
				changeColor();
			}
		}
	
		if(Math.abs(vy) < Math.hypot(3,3)/2){
			double r = Math.hypot(vx, vy);
			vy = Math.signum(vy) * r / 2;
			vx = Math.signum(vx) * Math.sqrt(r*r - vy*vy);
		}
			
		if(!stuck){
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
// 		g.drawString("(" + row + ", " + col + ")", (float)(x ), (float)(y + height/2));//draw rows and cols
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
	public String typeToString(){
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
				return "Sticky Paddle: Activate to catch and release balls for 15s";
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
		setRect(screenW/2 - width/2, screenH - 80 - height/3);
		//System.out.println(toString());
	}
	
	public String toString(){
		return "PowerUp - Type: " + type + " - " + pupRect.toString().split("Double")[1];
	}
	
	@Override
	public void paint(Graphics2D g){
		g.setColor(powerColor);
		g.fill(pupRect);
		g.setColor(Color.black);
		g.setFont(new Font("Courier", Font.BOLD, 30));
		g.drawString(initials, (int)(x - width/2 + 2), (int)(y + height*.2));
	}
	
	// public void displayMoreInfo(Graphics2D g){
// 		if( p.type != PowerUp.Type.POWER_BALL ){
// 			g.setFont(gameFont.deriveFont(25f));
// 			bg.drawString(p.typeToString(), screenWidth * .1f, (float)(screenHeight - getFontSize(bg)/2));	
// 		}
// 		else{
// 			g.setFont(gameFont.deriveFont(20f));
// 			g.drawString(p.typeToString(), 5, (float)(screenHeight - getFontSize(bg)/2));			
// 		}
// 	
// 	
// 	}
}

/*class Settings extends JPopupMenu{
	double x, y, width, height;
	Rectangle2D icon;
	boolean reshow = false;

	public Settings(){
		width = height = 50;
		x = GUI.screenWidth - width - 5;
		y = GUI.screenHeight - 80 - height - 5;
		icon = new Rectangle2D.Double(x, y, width, height);
		
		setLightWeightPopupEnabled(false);
		setLabel("Settings Popup");
		setFont(new Font("Courier", Font.BOLD, 30));
		
		
		add("Controls");
	}

	public void show(Component gui){
		show(gui, (gui.getWidth() - getPreferredSize().width)/2, (gui.getHeight() - getPreferredSize().height)/2);
	}
	
	public void paintComponent(Graphics2D g){
		
		
		if(!isVisible()){
			g.setColor(Color.white);
			g.fill(icon);
			g.setColor(Color.black);
			g.setFont(new Font("Courier", Font.PLAIN, 60));
			g.drawString("S", (float)((width - g.getFontMetrics().stringWidth("S"))/2 + x ), (float)( y + height - 10 ));
		}
	
	}

}*/

class Settings extends JPanel implements ChangeListener{
	JFrame frame = new JFrame("Settings Window");
	double x, y, width, height;
	Rectangle2D icon;
	GUI gui;
	JTextField colorText;
	JSlider slider;
	BufferedImage background;
	
	public Settings(GUI g){
		gui = g;
		width = height = 50;
		x = gui.screenWidth - width - 5;
		y = gui.screenHeight - 80 - height - 5;
		icon = new Rectangle2D.Double(x, y, width, height);
		
		this.setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
		// setBackground(Color.black);
		try{ background = ImageIO.read(SplashScreen.class.getResourceAsStream("images/BG5.jpg")); }
		catch(IOException e){ System.out.println("File Not Found"); }
		initializeSettings();
	}

	public void initializeSettings(){
		setPreferredSize(new Dimension((int)(600 * gui.gameScale), (int)(800*gui.gameScale)));
		
		/*Object rowdata[][] = new Object[gui.inputList.size() + 5][2];
		for(int i = 0; i < gui.inputList.size(); i++){
			String[] str = gui.inputList.get(i).split("\\s-\\s");
			rowdata[i+4][0] = "\t" + str[0];
			rowdata[i+4][1] = str[1];
		}
		Object colNames[] = {"Col1", "Col2"};
		JTable table = new JTable(rowdata, colNames);
		add(table, BorderLayout.CENTER);*/
		
		slider = new JSlider(0, 100);
		slider.addChangeListener(this);
		slider.setForeground(Color.white);
		slider.setBorder(BorderFactory.createEmptyBorder(0,0,0,15));

		colorText = new JTextField("" + slider.getValue());
		colorText.setColumns(2);
		colorText.setEditable(false);
		
		JPanel group = new JPanel(){
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
            public Dimension getPreferredSize() {
                return new Dimension(super.getPreferredSize().width,
                                     slider.getPreferredSize().height * 2);
            }
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }
        };
		group.setOpaque(false);
		group.setLayout(new BoxLayout(group, BoxLayout.LINE_AXIS));
		//group.setBackground(Color.white);
		group.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createTitledBorder("Paddle Color"),
                        BorderFactory.createEmptyBorder(5,5,5,5)));
		group.add(slider);
		group.add(colorText);
		
		add(group);
		
		frame.setContentPane(this);
		frame.pack();
	}
	
	public void paintComponent(Graphics g){
		g.setColor(Color.cyan);
		g.drawImage(background, 0, 0, getPreferredSize().width, getPreferredSize().height, this);
		//g.fillRect(0, 0, getPreferredSize().width, getPreferredSize().height);
	}
	
	@Override
	public void stateChanged(ChangeEvent e){
		JSlider source = (JSlider) e.getSource();
		float value = source.getValue() / 100.0f; //gets percentage
		colorText.setText("" + source.getValue());
		gui.paddle.paddleColor = Color.getHSBColor(value, 1.0f, 1.0f);
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
				//System.out.println(gameScale);
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
		
		//System.out.println(screenWidth - metrics.stringWidth(str));
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









































