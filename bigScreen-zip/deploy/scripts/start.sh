#!/bin/sh

#set($pidId = "$"+"!")
cd ${workspace}/${app_name}
pidFile="${app_name}.pid"
pidTmpFile="${app_name}.tmp.pid"

if [ -f "$pidFile" ]
then
    rm -f $pidFile
fi

jarName=$(ls *.jar)
fullJarPath=${workspace}/${app_name}/
nohup java ${jvm_args} ${jvm_gc_args} ${log4j2_args} -jar -DAppPID $fullJarPath$jarName >/dev/null  &

echo $pidId > ${pidTmpFile}