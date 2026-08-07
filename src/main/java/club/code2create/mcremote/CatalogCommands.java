package club.code2create.mcremote;

/**
 * b3 {@code catalog.get} command（wire-format-design §7.2.1）。
 */
public final class CatalogCommands {
    private final RemoteSession session;
    private final CatalogService catalogService;

    public CatalogCommands(RemoteSession session, CatalogService catalogService) {
        this.session = session;
        this.catalogService = catalogService;
    }

    public void handleGet(String[] args) {
        if (args.length != 0) {
            session.respondError(-32602, "invalid_params", null);
            return;
        }
        // catalog 配送は認証後のみ（§7.2）。auth enforcement の開発トグルが OFF でも、
        // token 無し hello を catalog 取得権限へ昇格させない。
        if (session.getBoundUuid() == null) {
            session.respondError(-32000, "auth_required", null);
            return;
        }
        session.respondResult(catalogService.getResponse());
    }
}
