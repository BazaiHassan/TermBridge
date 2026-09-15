# `go` from PATH, else the newest toolchain unpacked under ~/sdk (README: "Building behind
# regional blocks"). Override with `make GO=/path/to/go`.
GO      ?= $(or $(shell command -v go 2>/dev/null),$(lastword $(sort $(wildcard $(HOME)/sdk/go*/bin/go))))
ifeq ($(GO),)
$(error Go 1.23+ not found on PATH or in ~/sdk/go*/bin — see README "Building behind regional blocks")
endif

# proxy.golang.org and sum.golang.org are geo-blocked here; goproxy.cn serves both.
# An explicit GOPROXY in the environment wins.
export GOPROXY ?= https://goproxy.cn,direct

BIN     := bin
VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo 0.1.0-dev)
LDFLAGS := -s -w -X main.version=$(VERSION)
AGENT   := ./agent/cmd/termbridge

DESKTOP := ./agent/cmd/termbridge-desktop
# The desktop app uses Fyne 2.8 (cgo + OpenGL); its GLFW 3.4 compiles both the X11 and the
# Wayland backend, so it needs both sets of headers:
#   Fedora:  sudo dnf install gcc libX11-devel libXcursor-devel libXrandr-devel libXinerama-devel \
#                    libXi-devel libXxf86vm-devel mesa-libGL-devel wayland-devel libxkbcommon-devel
#   Ubuntu:  sudo apt install gcc libgl1-mesa-dev xorg-dev libxkbcommon-dev libwayland-dev
# Wayland-only build without the X11 headers: make desktop DESKTOP_TAGS="desktop wayland"
DESKTOP_TAGS ?= desktop

RELAY := ./relay/cmd/relay

.PHONY: all build relay desktop install-desktop cross packages test race vet lint run clean

all: vet test build

build:
	$(GO) build -trimpath -ldflags '$(LDFLAGS)' -o $(BIN)/termbridge $(AGENT)

# The relay for a VPS (see relay/deploy/): static, no cgo.
relay:
	CGO_ENABLED=0 GOOS=linux $(GO) build -trimpath -ldflags '$(LDFLAGS)' -o $(BIN)/termbridge-relay $(RELAY)

desktop:
	CGO_ENABLED=1 $(GO) build -tags '$(DESKTOP_TAGS)' -trimpath -ldflags '$(LDFLAGS)' -o $(BIN)/termbridge-desktop $(DESKTOP)

# Installs the app into ~/.local/bin and adds it to the applications menu.
install-desktop: desktop
	install -Dm755 $(BIN)/termbridge-desktop $(HOME)/.local/bin/termbridge-desktop
	$(HOME)/.local/bin/termbridge-desktop --install-launcher

# Compile check for every target OS (Windows PTY lands in phase 3).
cross:
	GOOS=linux   GOARCH=amd64 $(GO) build -trimpath -ldflags '$(LDFLAGS)' -o $(BIN)/termbridge-linux-amd64 $(AGENT)
	GOOS=linux   GOARCH=arm64 $(GO) build -trimpath -ldflags '$(LDFLAGS)' -o $(BIN)/termbridge-linux-arm64 $(AGENT)
	GOOS=darwin  GOARCH=arm64 $(GO) build -trimpath -ldflags '$(LDFLAGS)' -o $(BIN)/termbridge-darwin-arm64 $(AGENT)
	GOOS=windows GOARCH=amd64 $(GO) build -trimpath -ldflags '$(LDFLAGS)' -o $(BIN)/termbridge-windows-amd64.exe $(AGENT)

# Linux packages in dist/: termbridge (.deb, .rpm; amd64 and arm64: the CLI and a systemd user
# unit) and termbridge-desktop (.deb, .rpm; this machine's architecture). Needs nfpm:
#   go install github.com/goreleaser/nfpm/v2/cmd/nfpm@latest
NFPM        ?= $(or $(shell command -v nfpm 2>/dev/null),$(wildcard $(HOME)/go/bin/nfpm))
# Package versions must start with a digit: a bare commit hash (CI's shallow clone) becomes 0.0.0-dev+<hash>.
# (The case patterns use the "(pattern)" form: a bare ")" would end $(shell …) early.)
PKG_VERSION := $(shell v='$(patsubst v%,%,$(VERSION))'; case "$$v" in ([0-9]*) echo "$$v";; (*) echo "0.0.0-dev+$$v";; esac)
HOST_ARCH   := $(shell $(GO) env GOARCH)

packages: desktop
	@test -n "$(NFPM)" || { echo "nfpm not found: go install github.com/goreleaser/nfpm/v2/cmd/nfpm@latest"; exit 1; }
	mkdir -p dist $(BIN)/pkg
	cp $(BIN)/termbridge-desktop $(BIN)/pkg/termbridge-desktop
	$(BIN)/termbridge-desktop --write-icon $(BIN)/pkg/io.termbridge.desktop.png
	for arch in amd64 arm64; do \
		CGO_ENABLED=0 GOOS=linux GOARCH=$$arch $(GO) build -trimpath -ldflags '$(LDFLAGS)' -o $(BIN)/pkg/termbridge $(AGENT) || exit 1; \
		for fmt in deb rpm; do \
			ARCH=$$arch VERSION=$(PKG_VERSION) $(NFPM) pkg -f packaging/nfpm-cli.yaml -p $$fmt -t dist/ || exit 1; \
		done; \
	done
	for fmt in deb rpm; do \
		ARCH=$(HOST_ARCH) VERSION=$(PKG_VERSION) $(NFPM) pkg -f packaging/nfpm-desktop.yaml -p $$fmt -t dist/ || exit 1; \
	done
	ls -la dist

test:
	$(GO) test ./...

race:
	$(GO) test -race ./...

vet:
	$(GO) vet ./...

lint:
	golangci-lint run ./...

run: build
	./$(BIN)/termbridge run --insecure-dev

clean:
	rm -rf $(BIN) dist
