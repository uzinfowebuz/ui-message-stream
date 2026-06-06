package uz.uzinfoweb.uimessagestream.spring;

/**
 * Decides whether a tool call must be approved by a human before it runs — the server half of the v6
 * human-in-the-loop tool-approval flow.
 *
 * <p>Consulted by {@link RecordingToolCallingManager} for each tool call the model produces. When it
 * returns {@code true} and no decision for that call is present yet, the manager emits a
 * {@code tool-approval-request} and pauses the turn (the underlying tool is not executed) until the
 * client sends the user's decision back on the next request.
 *
 * <p>The default {@link #NONE} never requires approval, so HITL is strictly opt-in: a tool only gates
 * if you supply a policy that says so.
 */
@FunctionalInterface
public interface ApprovalPolicy {

    /**
     * @param toolName the tool being called
     * @param input    the call's parsed (JSON-deserialized) input arguments
     * @return {@code true} if a human must approve this call before it executes
     */
    boolean needsApproval(String toolName, Object input);

    /** Never requires approval — the opt-out default. */
    ApprovalPolicy NONE = (toolName, input) -> false;
}
