#!/bin/bash

#传入端口参数，多个端口用","分割 

if [ "x$1" == "x" ]; then 
    echo "No ports to open."
    exit 0  
fi

#备份默认分割符
OLD_IFS="$IFS" 
#设置分隔符为"," 
IFS="," 
#将参数转为数组
PORTS_ARR=($1) 
#将默认分隔符还原
IFS="$OLD_IFS"
curUser=`whoami`
if [ $curUser == root ];then
    if command -v firewall-cmd >/dev/null 2>&1; then 
      echo 'exists firewall-cmd' 
        firewallState=`firewall-cmd --state 2>&1`
        if [ "$firewallState" == "running" ] ; then

            ports=`firewall-cmd --zone=public --list-ports`
            echo "opened ports:$ports"

            for port in ${PORTS_ARR[@]} 
            do      
              result=`echo $ports | grep -w ${port}/tcp`
                if [ -z "$result" ] ; then
                    echo "open port ${port}"
                    firewall-cmd --zone=public --add-port=${port}/tcp --permanent
                    firewall-cmd --zone=public --add-port=${port}/udp --permanent
                fi
            done

            systemctl restart firewalld.service
        fi
    else
      echo 'no exists firewall-cmd'
        for port in ${PORTS_ARR[@]}
        do
            echo "open port ${port}"
            if ! iptables -nL|grep -w "dpt:${port}" >/dev/null;then
                iptables -I INPUT -p tcp --dport ${port} -j ACCEPT
                iptables -I INPUT -p udp --dport ${port} -j ACCEPT
            fi
        done
    fi
fi