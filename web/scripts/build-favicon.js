/**
 * Generates a rounded-corner version of the logo for use as favicon.
 * Run: node scripts/build-favicon.js
 */
const sharp = require('sharp');
const path = require('path');
const fs = require('fs');

const publicDir = path.join(__dirname, '..', 'public');
const inputPath = path.join(publicDir, 'green-sheep.png');
const outputPath = path.join(publicDir, 'favicon-rounded.png');

const size = 192;
const zoom = 1.25; // zoom in (1 = no zoom, 1.25 = 25% closer)
const radius = Math.round(size * 0.22); // ~22% rounded corners

const scaledSize = Math.round(size * zoom);
const left = Math.floor((scaledSize - size) / 2);
const top = Math.floor((scaledSize - size) / 2);

const roundedMask = Buffer.from(
  `<svg><rect width="${size}" height="${size}" rx="${radius}" ry="${radius}" fill="white"/></svg>`
);

sharp(inputPath)
  .resize(scaledSize, scaledSize)
  .extract({ left, top, width: size, height: size })
  .composite([{ input: roundedMask, blend: 'dest-in' }])
  .png()
  .toFile(outputPath)
  .then(() => console.log('Favicon written to public/favicon-rounded.png'))
  .catch((err) => {
    console.error(err);
    process.exit(1);
  });
