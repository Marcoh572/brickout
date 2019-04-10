javac brickout.java
jar cfm BrickoutMH.jar Manifest.txt *

JAVA_HOME=`/usr/libexec/java_home -v 9.0.4`
APP_DIR_NAME=BrickoutMH_Mac.app

#-deploy -Bruntime=/Library/Java/JavaVirtualMachines/jdk-9.0.4.jdk/Contents/Home \
javapackager \
  -deploy -Bruntime=${JAVA_HOME} -Bicon=images/gameLogo2.icns\
  -native image \
  --limit-modules java.base,java.desktop \
  -srcdir . \
  -srcfiles BrickoutMH.jar \
  -outdir . \
  -outfile ${APP_DIR_NAME} \
  -appclass brickout \
  -name "BrickoutMH" \
  -title "Brickout" \
  -nosign \
  #-v