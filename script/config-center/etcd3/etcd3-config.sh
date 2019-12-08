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

# etcd REST API v3.

if [[ $# != 1 ]]; then
	echo "./etcd3-config.sh etcd3Addr"
	exit -1
fi
etcd3Addr=$1
contentType="Content-type:application/json;charset=UTF-8"
echo "set etcd3Addr=$etcd3Addr"

error=0
for line in $(cat $(dirname "$PWD")/config.txt); do
    key=${line%%=*}
	value=${line#*=}
	echo "set" "${key}" "=" "${value}"

	keyBase64=$(printf "%s""$key" | base64)
	valueBase64=$(printf "%s""$value" | base64)
	echo "base64 >>>" "${keyBase64}" "=" "${valueBase64}"

    result=$(curl -X POST -H ${contentType} -d "{\"key\": \"$keyBase64\", \"value\": \"$valueBase64\"}" "http://$etcd3Addr/v3/kv/put")

    echo "response:$result"

    if [[ -z ${result} ]]; then
        echo "Please check the cluster status."
        exit -1
    fi

    if [[ ${result} =~ "error" || ${result} =~ "code" ]]; then
		(( error++ ))
	fi
done

if [[ ${error} -eq 0 ]]; then
	echo "init etcd3 config finished, please start seata-server."
else
	echo "init etcd3 config fail."
fi
exit 0