//go:build desktop

package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"math"

	"fyne.io/fyne/v2"
)

// Icons are drawn in code (no image assets): the shell prompt ">_" on ink,
// matching the Android launcher icon. The live variant adds a coral dot, so
// the tray shows at a glance that a shell is open (architecture §8.8).
var (
	iconIdle = fyne.NewStaticResource("termbridge.png", renderIcon(256, false))
	iconLive = fyne.NewStaticResource("termbridge-live.png", renderIcon(256, true))
)

func renderIcon(size int, live bool) []byte {
	img := image.NewNRGBA(image.Rect(0, 0, size, size))
	s := float64(size)
	fillRoundRect(img, s*0.04, s*0.04, s*0.96, s*0.96, s*0.22, colInk)
	w := s * 0.075
	stroke(img, s*0.28, s*0.34, s*0.48, s*0.50, w, colMint)
	stroke(img, s*0.48, s*0.50, s*0.28, s*0.66, w, colMint)
	stroke(img, s*0.56, s*0.66, s*0.74, s*0.66, w, colMint)
	if live {
		disc(img, s*0.80, s*0.20, s*0.15, colInk)
		disc(img, s*0.80, s*0.20, s*0.11, colCoral)
	}
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}

// cover returns the anti-aliased coverage for a signed distance (≤0 inside).
func cover(d float64) float64 { return math.Max(0, math.Min(1, 0.5-d)) }

func blend(img *image.NRGBA, x, y int, c color.NRGBA, a float64) {
	if a <= 0 {
		return
	}
	dst := img.NRGBAAt(x, y)
	mix := func(s, d uint8) uint8 { return uint8(float64(s)*a + float64(d)*(1-a)) }
	img.SetNRGBA(x, y, color.NRGBA{mix(c.R, dst.R), mix(c.G, dst.G), mix(c.B, dst.B), uint8(math.Max(float64(dst.A), a*255))})
}

func fillRoundRect(img *image.NRGBA, x0, y0, x1, y1, r float64, c color.NRGBA) {
	cx, cy, hw, hh := (x0+x1)/2, (y0+y1)/2, (x1-x0)/2, (y1-y0)/2
	b := img.Bounds()
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			dx := math.Max(math.Abs(float64(x)+0.5-cx)-(hw-r), 0)
			dy := math.Max(math.Abs(float64(y)+0.5-cy)-(hh-r), 0)
			blend(img, x, y, c, cover(math.Hypot(dx, dy)-r))
		}
	}
}

func stroke(img *image.NRGBA, x0, y0, x1, y1, width float64, c color.NRGBA) {
	vx, vy := x1-x0, y1-y0
	l2 := vx*vx + vy*vy
	b := img.Bounds()
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			px, py := float64(x)+0.5, float64(y)+0.5
			t := math.Max(0, math.Min(1, ((px-x0)*vx+(py-y0)*vy)/l2))
			d := math.Hypot(px-(x0+t*vx), py-(y0+t*vy)) - width/2
			blend(img, x, y, c, cover(d))
		}
	}
}

func disc(img *image.NRGBA, cx, cy, r float64, c color.NRGBA) {
	b := img.Bounds()
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			blend(img, x, y, c, cover(math.Hypot(float64(x)+0.5-cx, float64(y)+0.5-cy)-r))
		}
	}
}
