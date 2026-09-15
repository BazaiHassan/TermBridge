//go:build desktop

package main

import (
	_ "embed"
	"image/color"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/theme"
)

// JetBrains Mono (OFL-1.1), the same face the phone's terminal uses.
var (
	//go:embed assets/JetBrainsMono-Regular.ttf
	monoRegular []byte
	//go:embed assets/JetBrainsMono-Bold.ttf
	monoBold []byte
)

// The app palette, shared with the Android app (core-ui TbPalette).
var (
	colInk         = color.NRGBA{0x0B, 0x0E, 0x14, 0xFF}
	colSurface     = color.NRGBA{0x11, 0x15, 0x1C, 0xFF}
	colSurfaceHigh = color.NRGBA{0x17, 0x1C, 0x25, 0xFF}
	colOutline     = color.NRGBA{0x2A, 0x32, 0x42, 0xFF}
	colText        = color.NRGBA{0xE6, 0xED, 0xF3, 0xFF}
	colMuted       = color.NRGBA{0x8B, 0x96, 0xA8, 0xFF}
	colMint        = color.NRGBA{0x3D, 0xDC, 0x97, 0xFF}
	colAmber       = color.NRGBA{0xFF, 0xB4, 0x54, 0xFF}
	colCoral       = color.NRGBA{0xFF, 0x5F, 0x6D, 0xFF}
)

type tbTheme struct {
	base           fyne.Theme
	mono, monoBold fyne.Resource
}

func newTheme() fyne.Theme {
	return &tbTheme{
		base:     theme.DefaultTheme(),
		mono:     fyne.NewStaticResource("JetBrainsMono-Regular.ttf", monoRegular),
		monoBold: fyne.NewStaticResource("JetBrainsMono-Bold.ttf", monoBold),
	}
}

func (t *tbTheme) Color(n fyne.ThemeColorName, _ fyne.ThemeVariant) color.Color {
	switch n {
	case theme.ColorNameBackground:
		return colInk
	case theme.ColorNameForeground:
		return colText
	case theme.ColorNamePrimary, theme.ColorNameFocus, theme.ColorNameSuccess:
		return colMint
	case theme.ColorNameForegroundOnPrimary:
		return color.NRGBA{0x04, 0x13, 0x0C, 0xFF}
	case theme.ColorNameButton:
		return colSurfaceHigh
	case theme.ColorNameInputBackground, theme.ColorNameHeaderBackground, theme.ColorNameMenuBackground, theme.ColorNameOverlayBackground:
		return colSurface
	case theme.ColorNameSeparator, theme.ColorNameInputBorder:
		return colOutline
	case theme.ColorNamePlaceHolder, theme.ColorNameDisabled:
		return colMuted
	case theme.ColorNameHover:
		return color.NRGBA{0xFF, 0xFF, 0xFF, 0x14}
	case theme.ColorNamePressed:
		return color.NRGBA{0xFF, 0xFF, 0xFF, 0x22}
	case theme.ColorNameSelection:
		return color.NRGBA{0x3D, 0xDC, 0x97, 0x40}
	case theme.ColorNameWarning:
		return colAmber
	case theme.ColorNameError:
		return colCoral
	case theme.ColorNameShadow:
		return color.NRGBA{0, 0, 0, 0x66}
	}
	return t.base.Color(n, theme.VariantDark)
}

func (t *tbTheme) Font(s fyne.TextStyle) fyne.Resource {
	if s.Monospace {
		if s.Bold {
			return t.monoBold
		}
		return t.mono
	}
	return t.base.Font(s)
}

func (t *tbTheme) Icon(n fyne.ThemeIconName) fyne.Resource { return t.base.Icon(n) }

func (t *tbTheme) Size(n fyne.ThemeSizeName) float32 {
	switch n {
	case theme.SizeNameInputRadius, theme.SizeNameSelectionRadius:
		return 8
	}
	return t.base.Size(n)
}
