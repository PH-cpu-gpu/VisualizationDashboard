#!/bin/bash

msg=`netstat -an|grep -w LISTEN|awk '{if($4 ~/:${app_server_port}$|:${rpc_protocol_port}$/) print $4}'|sed 's/:::/ /g'|sed 's/.*:/ /g'`
if [ -n "$msg" ];
then
    echo [error] the port$msg is already in used, please release the port and deploy it again. 1>&2
fi