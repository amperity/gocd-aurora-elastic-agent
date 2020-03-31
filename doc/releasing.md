# Releasing

1. Update the version number in these places:

   - [project.clj](../project.clj)
   - [plugin.xml](../resources/plugin.xml)

1. Update [CHANGELOG.md](./CHANGELOG.md). We follow the guidelines from
   [keepachangelog.com](http://keepachangelog.com/) and [Semantic
   Versioning](http://semver.org/)

1. Commit changes, create a PR, merge the PR into master.

1. Create a signed tag at the release commit. `git tag -s X.X.X -m "X.X.X
   Release" && git push origin X.X.X`

1. Build the plugin. `make plugin`

1. Create a release on the [plugin's GitHub release
   page](https://github.com/amperity/gocd-aurora-elastic-agent/releases).
   Upload the jarfile you built in the previous step. It was created at
   `target/plugin/gocd-aurora-elastic-agent-0.2.0-SNAPSHOT-plugin.jar`.
