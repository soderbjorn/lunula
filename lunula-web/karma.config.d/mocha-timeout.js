// Raise mocha's per-test timeout for the DOM-level shell tests
// (SidebarMinimizedRestoreTest mounts the real app shell and polls the
// DOM through async persister/snapshot round-trips; the default 2s is
// too tight for the multi-stage waits on CI-grade hardware).
config.set({
    client: {
        mocha: {
            timeout: 10000,
        },
    },
});
