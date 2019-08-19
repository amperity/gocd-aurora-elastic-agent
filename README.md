gocd-aurora-elastic-agent
=========================

<img height="80" width="80" align="right" src="resources/amperity/gocd/agent/aurora/logo.svg"/>

A plugin for [GoCD](https://www.gocd.org/) providing elastic agent support via
[Apache Aurora](https://aurora.apache.org/).

<br/>


## Installation

Releases are published on the [GitHub project](https://github.com/amperity/gocd-aurora-elastic-agent/releases).
Download the latest version of the plugin and
[place it in your server's external plugin directory](https://docs.gocd.org/current/extension_points/plugin_user_guide.html).
Once installed, restart the GoCD server to load the plugin. The plugin should
appear in your server's Admin - Plugins page when it is back up.


## Configuration

The plugin is configured through the Admin - Elastic Profiles page. You can add
[cluster profiles](doc/clusters.md) which correspond to the Aurora clusters you
want to run agents on. Within each cluster you can create multiple
[agent profiles](doc/agents.md) defining the resources and tools necessary to
run certain types of jobs.

Once you've defined the agents you need in each cluster, you can begin assigning
the elastic agent profiles to pipeline jobs. Those jobs will then ask for
elastic agents to launch and run on.


## Local Development

If you're planning to work on the plugin code locally, make sure to read and
understand the [agent scheduler](doc/scheduler.md) and state transitions. See
the [`gocd`](gocd) directory for running a local GoCD server.


## License

Licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file
for rights and restrictions.
