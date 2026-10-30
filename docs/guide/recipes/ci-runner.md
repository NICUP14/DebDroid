[← Recipes](README.md)

# CI Runner on a Phone

Run your builds on hardware you already own, instead of paying for minutes.

## What you'll end up with

A GitHub Actions or GitLab runner on the phone, picking up jobs from your
repositories.

## Is a phone fast enough?

For many projects, yes. A 2015-era phone is roughly a Raspberry Pi 3, and it
has a battery so a power cut does not kill a build mid-run.

It suits linting, unit tests, small builds and scheduled jobs. It does not suit
compiling anything large — and ARM-native builds are a genuine advantage if you
ship ARM containers, since you avoid emulation entirely.

## What it needs

- [Background mode](../keep-it-running.md) set up.
- 4 GB of space or more; builds accumulate.
- Swap, which build tools will use. See
  [keep it running](../keep-it-running.md#give-it-more-memory).

## GitHub Actions

```bash
su
sh /sdcard/debdroid/debdroid.sh start
sh /sdcard/debdroid/debdroid.sh attach
```

Inside:

```bash
apt update
apt install -y curl jq git

adduser --disabled-password --gecos "" runner
su - runner

mkdir actions-runner && cd actions-runner
curl -o runner.tar.gz -L \
    https://github.com/actions/runner/releases/download/v2.321.0/actions-runner-linux-arm64-2.321.0.tar.gz
tar xzf runner.tar.gz
```

Get a registration token from your repository under
*Settings → Actions → Runners → New self-hosted runner*, then:

```bash
./config.sh --url https://github.com/youruser/yourrepo --token <token>
./run.sh &
```

## GitLab

```bash
apt install -y gitlab-runner
gitlab-runner register   # follow the prompts
gitlab-runner run &
```

Choose the `shell` executor. Docker needs kernel features most phone kernels
lack, so it generally will not work here.

## Use it in a workflow

```yaml
jobs:
  test:
    runs-on: self-hosted
    steps:
      - uses: actions/checkout@v4
      - run: make test
```

## How to tell it worked

The runner shows as online in your repository settings. Push a commit and watch
the job pick up.

## If it didn't work

- **Runner goes offline when the screen turns off** — the device is sleeping.
  Check `debdroid.sh status` reports the wakelock as held.
- **Builds fail on memory** — set up swap, and avoid parallel jobs. One at a
  time is usually the right setting on a phone.
- **Docker steps fail** — expected. Phone kernels usually lack the namespaces
  and cgroup features containers need; use the shell executor.
- **The device gets hot and slows down** — phones have no fans and will
  throttle. Keep it somewhere with airflow, and out of a case.
- **Disk fills up** — clear old workspaces periodically, and
  [resize](../storage.md) if needed.

> [!NOTE]
> A self-hosted runner executes code from your repository. Only use one on
> repositories you control, and never on a public repository that accepts pull
> requests — anyone could run anything on your device.
