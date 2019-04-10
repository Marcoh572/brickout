JAVA_HOME=`/usr/libexec/java_home -v 9.0.4`
APP_DIR_NAME=BrickoutMH_Win.exe


#-deploy -Bruntime=/Library/Java/JavaVirtualMachines/jdk-9.0.4.jdk/Contents/Home \
javapackager \
  -deploy -Bruntime=${JAVA_HOME} -Bicon=images/gameLogo2_Win.ico \
  -native exe \
  --limit-modules java.base,java.desktop \
  -srcdir . \
  -srcfiles BrickoutMH.jar \
  -outdir . \
  -outfile ${APP_DIR_NAME} \
  -appclass brickout \
  -name "BrickoutMH_Win" \
  -title "Brickout" \
  -nosign \
  -v
  