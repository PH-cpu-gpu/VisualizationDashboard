#!/bin/bash

cd ${workspace}/${app_name}

pidFile="${app_name}.pid"

if [ -f "$pidFile" ]
then
    PID=$(cat ${pidFile})
    i=10
    while [[ $i -gt 0 ]];do
        sleep 2
        check_start=`ps axu | grep java | grep -w $PID |grep -v grep| awk '{printf $2}'`
        if [ -z "$check_start" ];
        then
            break
        fi
        ((i = i - 1))
    done
    if [[ $i -gt 0 ]]
    then
	    alias rm='rm'
        rm -f ${pidFile}
        echo 0
    else
        echo "No stopping!" 1>&2
    fi
else
    echo 0
fi
