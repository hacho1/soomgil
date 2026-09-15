export async function readImage(file) {
  if (!file || !["image/jpeg", "image/png", "image/webp"].includes(file.type)) throw new Error("JPG, PNG, WebP 사진을 선택해주세요.");
  if (file.size > 10 * 1024 * 1024) throw new Error("10MB 이하의 사진을 선택해주세요.");
  return new Promise((resolve, reject) => {
    const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = () => reject(new Error("사진을 읽을 수 없습니다.")); reader.readAsDataURL(file);
  });
}
function loadImage(url) {
  return new Promise((resolve, reject) => { const image = new Image(); image.onload = () => resolve(image); image.onerror = () => reject(new Error("사진을 열지 못했어요.")); image.src = url; });
}
export async function cropImage(source, crop, rotation = 0) {
  const image = await loadImage(source);
  const radians = rotation * Math.PI / 180;
  const width = Math.abs(Math.cos(radians) * image.width) + Math.abs(Math.sin(radians) * image.height);
  const height = Math.abs(Math.sin(radians) * image.width) + Math.abs(Math.cos(radians) * image.height);
  const canvas = document.createElement("canvas"); canvas.width = Math.ceil(width); canvas.height = Math.ceil(height);
  const ctx = canvas.getContext("2d"); ctx.translate(width / 2, height / 2); ctx.rotate(radians); ctx.drawImage(image, -image.width / 2, -image.height / 2);
  const output = document.createElement("canvas"); output.width = 512; output.height = 512;
  const out = output.getContext("2d"); out.fillStyle = "#ffffff"; out.fillRect(0, 0, 512, 512);
  out.drawImage(canvas, crop.x, crop.y, crop.width, crop.height, 0, 0, 512, 512);
  return output.toDataURL("image/jpeg", 0.86);
}
export async function resizeImage(file) {
  const source = await readImage(file), image = await loadImage(source);
  const scale = Math.min(1, 1400 / Math.max(image.width, image.height));
  const canvas = document.createElement("canvas"); canvas.width = image.width * scale; canvas.height = image.height * scale;
  canvas.getContext("2d").drawImage(image, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL("image/jpeg", 0.82);
}
