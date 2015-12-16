#!/bin/bash

SBT_BOOT_DIR=$HOME/.sbt/boot/

if [ ! -d "$SBT_BOOT_DIR" ]; then
  mkdir -p $SBT_BOOT_DIR
fi

# look whether we are in TeamCity and set up SBT appropriately
if [ -n "$BUILD_NUMBER" ]; then
    TC_PARAMS="-Dsbt.log.noformat=true"
    export JDK_HOME=/usr/lib/jvm/java-1.8.0
    export JAVA_HOME=${JDK_HOME}

    echo "********** Java version **********"
    ${JAVA_HOME}/bin/java -version
    echo "**********************************"
else
    TC_PARAMS=""
fi

${JAVA_HOME}/bin/java -Xmx768M -XX:+UseCompressedOops -XX:MaxPermSize=384m \
    ${TC_PARAMS} \
	-Dsbt.boot.directory=$SBT_BOOT_DIR \
	${SBT_EXTRA_PARAMS} \
	-jar `dirname $0`/sbt-launch.jar "$@"

