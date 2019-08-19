Cluster Profile Configuration
=============================

A cluster profile in GoCD maps to a real Aurora cluster you'd like to run agents
on. The profile settings tell the plugin how to contact the cluster and what
role and environment to run the agents under.

At runtime, the plugin will periodically check on the resource quota available
to it in Aurora. The scheduler will attempt to keep the total agent resource
usage within the quota if one is set.

**Note:** the plugin currently assumes that you will have at most one
profile per Aurora cluster, so avoid overlapping them.


## Cluster Settings

- **Aurora URL**

  Location of the Aurora scheduler API. Usually ends in `/api`.

- **Aurora Cluster**

  The name of the Aurora cluster. Used as the first segment of each agent job.

- **Aurora Role**

  The user to run the GoCD agents as.

- **Aurora Environment**

  The environment to run the GoCD agent job under. This should generally be
  `prod`.


## Agent Settings

- **Server API URL**

  The direct GoCD server URL to contact. Often this should contain the 8153 or
  8154 port and ends in `/go`.

- **Agent Source URL** (optional)

  A URL to fetch to download the agent ZIP archive. Defaults to the public GoCD
  download URL if not set.
