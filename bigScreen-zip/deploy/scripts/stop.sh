#!/bin/bash
cd ${workspace}/${app_name}

pidFile="${app_name}.pid"

# delete pid file
function deletePidFile(){
	if [ -f "$1" ]
	then
	    alias rm='rm'
		rm -f $1
	fi
}

PID=$(cat ${pidFile})
if [ "-$PID" = "-" ];then
    echo "no process existes!"
	deletePidFile $pidFile
else
    #先优雅停机
    kill $PID
	sleep 10s
    PID=`ps axu | grep java | grep -w $PID | grep -v grep | awk {'print $2'}`
    if [ "-$PID" = "-" ];then
        echo "kill $PID process success!"
		deletePidFile $pidFile  
    else
        #强制停机
        kill -9 $PID
        echo "kill -9 $PID process success!"
		deletePidFile $pidFile  
    fi
fi