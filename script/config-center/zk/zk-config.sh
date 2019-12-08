#!/usr/bin/env bash
# ----------------------------------------------------------------------------
#  Copyright 2001-2006 The Apache Software Foundation.
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
# ----------------------------------------------------------------------------
#
#   Copyright (c) 2001-2006 The Apache Software Foundation.  All rights
#   reserved.


# The purpose is to sync the local configuration(config.txt) to zk.
# This script need to rely on zk.

#if [[ -z "$1" ]]; then
#	echo "zk address is empty, Please enter zk address!"
#	exit 1
#fi
#
#if [[ -z "$2" ]]; then
#	echo "zkHome is empty, please enter zk home!"
#	exit 1
#fi

for line in $(cat zk-params.txt); do
	key=${line%%=*}
	value=${line#*=}
	case ${key} in
	zkAddr)
		zkAddr=${value}
		;;
	zkHome)
		zkHome=${value}
		;;
	*)
		echo "invalid param"
		exit -1
		;;
	esac
done

if [[ -z ${zkAddr} || -z ${zkHome} ]]; then
	echo "Incomplete parameters, please fill in the complete parameters: zkAddr:$zkAddr, zkHome:$zkHome"
	exit -1
fi

root="/seata"
tempLog=$(mktemp -t zk-config.log)

echo "zk address is $zkAddr"
echo "zk home is $zkHome"
echo "zk config root node is $root"

function check_node() {
	$2/bin/zkCli.sh -server $1 ls ${root} >/dev/null 2>${tempLog}
}

function create_node() {
	$2/bin/zkCli.sh -server $1 create ${root} "" >/dev/null
}

function create_subNode() {
	$2/bin/zkCli.sh -server $1 create "${root}/$3" "$4" >/dev/null
}

function delete_node() {
	$2/bin/zkCli.sh -server $1 rmr ${root} "" >/dev/null
}

check_node ${zkAddr} ${zkHome}

if [[ $(cat ${tempLog}) =~ "No such file or directory" ]]; then
	echo "zk home is error, please enter correct zk home!"
	exit -1
elif [[ $(cat ${tempLog}) =~ "Exception" ]]; then
	echo "Exception error, please check zk cluster status or if the zk address is entered correctly!"
	exit -1
elif [[ $(cat ${tempLog}) =~ "Node does not exist" ]]; then
	create_node ${zkAddr} ${zkHome}
else
	read -p "${root} node already exists, now delete ${root} node in zk, y/n: " result
	if [[ ${result} == "y" ]]; then
		echo "delete ${root} node..."
		delete_node ${zkAddr} ${zkHome}
		create_node ${zkAddr} ${zkHome}
	else
		exit 0
	fi
fi

for line in $(cat $(dirname "$PWD")/config.txt); do
	key=${line%%=*}
	value=${line#*=}
	echo "\r\n set" "${key}" "=" "${value}"
	create_subNode ${zkAddr} ${zkHome} ${key} ${value}
done
exit 0