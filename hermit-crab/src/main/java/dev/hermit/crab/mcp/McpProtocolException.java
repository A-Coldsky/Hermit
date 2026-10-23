package dev.hermit.crab.mcp;

/** JSON-RPC params that do not match the method schema. Tool argument errors stay tool results. */
final class McpProtocolException extends RuntimeException {

    McpProtocolException() {
        super(McpErrors.invalidParams().message());
    }
}
