# Auto-syncing the Voxy NeoForge mod into your `.minecraft` repo

This directory contains the GitHub Actions workflow that pulls the latest
`voxy-neoforge-*.jar` built by this repo into your
[`.minecraft`](https://github.com/JustinTHChapman/.minecraft) repo's `mods/`
folder and commits it.

## Install (one-time)

1. In your `.minecraft` repo, create the file
   `.github/workflows/sync-voxy.yml` with the contents of
   [`sync-voxy.yml`](sync-voxy.yml).
2. Commit and push. No secrets need to be configured — the workflow uses the
   built-in `GITHUB_TOKEN` and the voxy repo's Release is public.
3. (Optional) In the `.minecraft` repo settings → Actions → General, make sure
   "Read and write permissions" is enabled for the `GITHUB_TOKEN` (default for
   public repos is read-only).

## How it works

| Stage | What happens |
|---|---|
| Trigger | Runs **only** on manual dispatch via the Actions tab, or when this repo fires a `repository_dispatch` of type `voxy-release`. No scheduled runs. |
| Fetch | Calls the GitHub API for the rolling release `latest-neoforge-rewrite` and grabs the first non-sources `voxy-neoforge-*.jar` asset. |
| Replace | Deletes any pre-existing `mods/voxy-*.jar` whose filename differs from the new one. |
| Commit | If anything actually changed, commits with the upstream commit SHA in the message. No-op otherwise. |

## Triggering an immediate sync from this repo

If you want the `.minecraft` workflow to run as soon as voxy publishes a new
build, add the following step to the **end** of the voxy `build` job in
[../../.github/workflows/build.yml](../../.github/workflows/build.yml):

```yaml
      - name: Notify .minecraft repo
        if: github.event_name == 'push' && github.ref == 'refs/heads/neoforge-rewrite'
        env:
          GH_TOKEN: ${{ secrets.MINECRAFT_REPO_DISPATCH_TOKEN }}
        run: |
          gh api repos/JustinTHChapman/.minecraft/dispatches \
            -f event_type=voxy-release \
            -f "client_payload[sha]=${{ github.sha }}"
```

This requires a Personal Access Token (classic) with `repo` scope, added as
`MINECRAFT_REPO_DISPATCH_TOKEN` in this repo's secrets. Without it the
`.minecraft` workflow only runs on manual dispatch.

## Conflict safety

- The workflow only touches files matching `mods/voxy-*.jar`.
- It never force-pushes and never rewrites history.
- If your `.minecraft` repo has a `mods/voxy-*.jar` from the old Fabric build,
  the first run will delete it and commit the NeoForge replacement.

## Removing the old custom-edits build

You can safely delete any old `voxy-*.jar` from your `mods/` folder before the
first sync; the workflow will add the new one regardless. The replacement
behaviour means *both* old and new can never coexist in `mods/`.
