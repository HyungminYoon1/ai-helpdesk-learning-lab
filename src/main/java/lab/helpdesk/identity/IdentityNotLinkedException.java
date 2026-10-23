package lab.helpdesk.identity;

public class IdentityNotLinkedException extends RuntimeException {
    public IdentityNotLinkedException() {
        super("IDENTITY_UNLINKED");
    }
}
