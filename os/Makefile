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
# The desktop app uses Fyne (cgo + OpenGL). X11 build needs e.g. on Fedora:
#   sudo dnf install gcc libX11-devel libXcursor-devel libXrandr-devel libXinerama-devel \
#                    libXi-devel libXxf86vm-devel mesa-libGL-devel
# Wayland-only build without X11 headers: make desktop DESKTOP_TAGS="desktop wayland"
DESKTOP_TAGS ?= desktop

.PHONY: all build desktop install-desktop cross test race vet lint run clean

all: vet test build

build:
	$(GO) build -trimpath -ldflags '$(LDFLAGS)' -o $(BIN)/termbridge $(AGENT)

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
	rm -rf $(BIN)
