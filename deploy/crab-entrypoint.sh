#!/bin/sh
# Crab 容器入口。控制台继续只听 127.0.0.1，不在这里放开回环限制。
# /bin/sh 在 eclipse-temurin 上是 dash，脚本保持 POSIX。
# 已有 $HERMIT_HOME/reef-bootstrap.json 时不再要求 Reef 环境变量，也不再为公钥做管理员登录。
# 环境变量和引导文件都没有时仍然启动，控制台可以之后导入引导文件。
set -eu

home="${HERMIT_HOME:-/var/lib/hermit}"
port="${HERMIT_CRAB_PORT:-7777}"
reef_url="${HERMIT_REEF_URL:-}"
reef_url="${reef_url%/}"
bootstrap="$home/reef-bootstrap.json"

case "$port" in
    ''|*[!0-9]*)
        echo "HERMIT_CRAB_PORT 必须是 1 到 65535 的整数" >&2
        exit 1
        ;;
esac
if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
    echo "HERMIT_CRAB_PORT 必须是 1 到 65535 的整数" >&2
    exit 1
fi

# 和 Reef 的入口一样：去掉空白后为空就不算配置了令牌。写进 config.yml 的仍是原始值，两边才能对上。
token_compact=$(printf '%s' "${HERMIT_REEF_AGENT_TOKEN:-}" | tr -d '[:space:]')
has_bootstrap=0
if [ -f "$bootstrap" ]; then
    has_bootstrap=1
fi

# 只给了 URL 或只给了令牌、又没有引导文件时，仍然拒绝启动。两者都空则允许，交给控制台导入。
if [ "$has_bootstrap" -eq 0 ]; then
    if [ -z "$reef_url" ] && [ -n "$token_compact" ]; then
        echo "必须设置 HERMIT_REEF_URL。" >&2
        exit 1
    fi
    if [ -n "$reef_url" ] && [ -z "$token_compact" ]; then
        echo "必须设置 HERMIT_REEF_AGENT_TOKEN，空令牌不会启动 Crab。" >&2
        exit 1
    fi
fi

mkdir -p "$home/data"

yaml_quote() {
    # 双引号 YAML 标量。控制字符没法放进这一行。
    # sed 会在输出末尾加换行，必须丢掉，否则标量会被拆成两行。
    if printf '%s' "$1" | grep -q '[[:cntrl:]]'; then
        return 1
    fi
    escaped=$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')
    printf '"%s"' "$escaped"
}

require_line() {
    if ! grep -F -x "$1" "$home/config.yml" >/dev/null; then
        echo "$2" >&2
        exit 1
    fi
}

is_pem() {
    [ -f "$1" ] && grep -q -- '-----BEGIN' "$1"
}

copy_pub() {
    src=$1
    if [ ! -f "$src" ]; then
        echo "签名公钥文件不存在: $src" >&2
        exit 1
    fi
    if ! is_pem "$src"; then
        echo "签名公钥不是 PEM: $src" >&2
        exit 1
    fi
    if [ "$src" -ef "$home/reef.pub" ]; then
        return 0
    fi
    cp "$src" "$home/reef.pub"
    chmod 644 "$home/reef.pub"
}

fetch_pub() {
    if [ -z "${HERMIT_REEF_ADMIN_USER:-}" ] || [ -z "${HERMIT_REEF_ADMIN_PASSWORD:-}" ]; then
        echo "还没有 reef.pub。设置 HERMIT_REEF_PUBLIC_KEY_FILE，或提供 HERMIT_REEF_ADMIN_USER 和 HERMIT_REEF_ADMIN_PASSWORD 以便登录 Reef 获取签名公钥。" >&2
        exit 1
    fi
    echo "waiting for reef login at ${reef_url}"
    login_token=""
    attempt=0
    while [ "$attempt" -lt 60 ]; do
        body=$(jq -n \
            --arg username "$HERMIT_REEF_ADMIN_USER" \
            --arg password "$HERMIT_REEF_ADMIN_PASSWORD" \
            '{username:$username,password:$password}')
        if response=$(curl -fsS --max-time 10 -X POST "${reef_url}/api/admin/login" \
            -H 'Content-Type: application/json' \
            -d "$body"); then
            login_token=$(printf '%s' "$response" | jq -r '.data.token // empty')
            if [ -n "$login_token" ] && [ "$login_token" != "null" ]; then
                break
            fi
            login_token=""
        fi
        attempt=$((attempt + 1))
        sleep 2
    done
    if [ -z "$login_token" ]; then
        echo "reef login did not succeed" >&2
        exit 1
    fi

    tmp_key=$(mktemp)
    if ! curl -fsS --max-time 10 "${reef_url}/api/admin/signing-key" \
        -H "Authorization: Bearer ${login_token}" \
        | jq -er '.data.publicKey' > "$tmp_key"; then
        rm -f "$tmp_key"
        echo "could not read reef signing key" >&2
        exit 1
    fi
    if ! is_pem "$tmp_key"; then
        rm -f "$tmp_key"
        echo "reef signing key was not a PEM" >&2
        exit 1
    fi
    mv "$tmp_key" "$home/reef.pub"
    chmod 644 "$home/reef.pub"
}

if [ -n "${HERMIT_CRAB_INSTANCE_ID:-}" ]; then
    instance=$(printf '%s' "$HERMIT_CRAB_INSTANCE_ID" | tr -d '[:space:]' | tr 'A-F' 'a-f')
    case "$instance" in
        [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f])
            ;;
        *)
            echo "HERMIT_CRAB_INSTANCE_ID 必须是 UUID" >&2
            exit 1
            ;;
    esac
    id_file="$home/data/instance-id"
    if [ -f "$id_file" ]; then
        existing=$(tr -d '[:space:]' < "$id_file" | tr 'A-F' 'a-f')
        if [ "$existing" != "$instance" ]; then
            echo "HERMIT_CRAB_INSTANCE_ID 和数据卷里的 data/instance-id 不一致，不会覆盖。" >&2
            exit 1
        fi
    else
        printf '%s\n' "$instance" > "$id_file"
        chmod 600 "$id_file"
    fi
fi

# 主密钥只来自环境变量或文件，不写进 config.yml，也不打进镜像。
# 空白的 HERMIT_MASTER_KEY 视为没设。compose 会把没填的变量传成空字符串，Java 会把它当成非法密钥。
master_from_env=0
if [ "${HERMIT_MASTER_KEY+x}" = x ]; then
    master_compact=$(printf '%s' "$HERMIT_MASTER_KEY" | tr -d '[:space:]')
    if [ -z "$master_compact" ]; then
        unset HERMIT_MASTER_KEY
    else
        master_from_env=1
    fi
fi
if [ "$master_from_env" -eq 1 ]; then
    echo "credential master key: HERMIT_MASTER_KEY"
elif [ -n "${HERMIT_MASTER_KEY_FILE:-}" ]; then
    src="$HERMIT_MASTER_KEY_FILE"
    dest="$home/data/master.key"
    if [ ! -f "$src" ]; then
        echo "HERMIT_MASTER_KEY_FILE 不存在: $src" >&2
        exit 1
    fi
    if [ ! "$src" -ef "$dest" ]; then
        if [ -f "$dest" ]; then
            src_sum=$(sha256sum "$src" | awk '{print $1}')
            dest_sum=$(sha256sum "$dest" | awk '{print $1}')
            if [ "$src_sum" != "$dest_sum" ]; then
                echo "数据卷里的 data/master.key 和 HERMIT_MASTER_KEY_FILE 不一致，不会覆盖。" >&2
                exit 1
            fi
        else
            cp "$src" "$dest"
            chmod 600 "$dest"
        fi
    fi
    echo "credential master key: $dest"
else
    # Java 只在数据目录还没有凭证时生成 data/master.key。已有凭证但文件缺失时拒绝启动，避免静默换钥。
    if [ -f "${home}/data/master.key" ]; then
        echo "credential master key: ${home}/data/master.key"
    else
        echo "credential master key: ${home}/data/master.key（尚未存在；仅当数据目录没有凭证时才会自动生成）"
    fi
fi

write_config() {
    reef_block=$1
    tmp_config=$(mktemp)
    {
        if [ -n "$reef_block" ]; then
            printf '%s\n' "$reef_block"
        fi
        cat <<EOF
server:
  host: "127.0.0.1"
  port: ${port}
sync:
  interval_seconds: 300
report:
  enabled: true
  interval_seconds: 300
EOF
    } > "$tmp_config"
    mv "$tmp_config" "$home/config.yml"
    chmod 600 "$home/config.yml"
    echo "wrote ${home}/config.yml"
}

require_listen() {
    require_line '  host: "127.0.0.1"' "config.yml 的 server.host 必须是 127.0.0.1。控制台只允许回环访问，容器里不会关掉这个限制。"
    require_line "  port: ${port}" "config.yml 里的 server.port 和 HERMIT_CRAB_PORT 不一致。改成同一个端口，或删掉 config.yml 后再启动。"
}

if [ "$has_bootstrap" -eq 1 ]; then
    chmod 600 "$bootstrap"
    if [ ! -f "$home/config.yml" ]; then
        # 令牌留在引导文件里。新生成的 config.yml 不复制环境变量中的 URL 或令牌。
        write_config ""
    else
        require_listen
    fi
elif [ -n "$reef_url" ] && [ -n "$token_compact" ]; then
    quoted_url=$(yaml_quote "$reef_url") || {
        echo "HERMIT_REEF_URL 无法写入 config.yml" >&2
        exit 1
    }
    quoted_token=$(yaml_quote "$HERMIT_REEF_AGENT_TOKEN") || {
        echo "HERMIT_REEF_AGENT_TOKEN 无法写入 config.yml" >&2
        exit 1
    }
    if [ ! -f "$home/config.yml" ]; then
        if is_pem "$home/reef.pub"; then
            :
        elif [ -n "${HERMIT_REEF_PUBLIC_KEY_FILE:-}" ]; then
            copy_pub "$HERMIT_REEF_PUBLIC_KEY_FILE"
        else
            fetch_pub
        fi
        if ! is_pem "$home/reef.pub"; then
            echo "reef.pub 不是 PEM" >&2
            exit 1
        fi
        write_config "reef:
  url: ${quoted_url}
  token: ${quoted_token}
  public_key: reef.pub"
    else
        require_line "  url: ${quoted_url}" "config.yml 里的 reef.url 和 HERMIT_REEF_URL 不一致。删掉数据卷里的 config.yml 后再启动（reef.pub 和 data/ 会保留）。"
        require_line "  token: ${quoted_token}" "config.yml 里的 reef.token 和 HERMIT_REEF_AGENT_TOKEN 不一致。Crab 必须和 Reef 用同一令牌。删掉数据卷里的 config.yml 后再启动（reef.pub 和 data/ 会保留）。"
        require_listen
    fi
else
    # 没有 Reef 环境变量，也没有引导文件。缺配置时生成一份不含 reef 的 config.yml。
    # 已经写过 reef 的旧配置保留，由 Java 继续使用。
    if [ ! -f "$home/config.yml" ]; then
        write_config ""
    else
        require_listen
    fi
fi

chown -R hermit:hermit "$home"
chmod 700 "$home"

exec runuser -u hermit -- java -cp "/app/crab.jar:/app/lib/*" dev.hermit.crab.CrabMain serve
