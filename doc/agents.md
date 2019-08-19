Agent Profile Configuration
===========================

An agent profile in GoCD specifies a resource profile and toolset required to
run a particular kind of job. For example, common profiles types would be
`build`, `test`, and `deploy`.

**NOTE:** Since agent profile ids need to be unique, a good practice is to
prefix them with the cluster name. For example, `aws-dev.test`.


## General Settings

- **Job Tag**

  This should be a short alphabetical prefix for the agent jobs in Aurora. This
  helps distinguish agent types from each other. This should be unique within a
  cluster!


## Agent Resources

- **CPU**

  Fractions of a CPU share to reserve for the job. Simple shell tasks may only
  need 0.25 CPU, while compute-heavy builds might need 4.0 or more.

- **Memory**

  Number of megabytes of memory to make available to the job. The agent may be
  killed if its usage exceeds this.

- **Disk**

  Number of megabytes of disk to make available to the job. The agent may be
  killed if its usage exceeds this.
