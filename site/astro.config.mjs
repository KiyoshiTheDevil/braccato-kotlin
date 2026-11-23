// The documentation site: guides written here, the API reference built by Dokka and put under /api/.
import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";

export default defineConfig({
	site: "https://braccato.kiyoshi.dev",
	integrations: [
		starlight({
			title: "braccato-kotlin",
			description: "Braccato's synchronized lyrics engine in Kotlin: parsers, timeline and a Compose Multiplatform renderer.",
			logo: {
				light: "./src/assets/logo-light.png",
				dark: "./src/assets/logo-dark.png",
				replacesTitle: true,
			},
			favicon: "/favicon.png",
			social: [{ icon: "github", label: "GitHub", href: "https://github.com/KiyoshiTheDevil/braccato-kotlin" }],
			editLink: { baseUrl: "https://github.com/KiyoshiTheDevil/braccato-kotlin/edit/main/site/" },
			customCss: ["./src/styles/braccato.css"],
			// Dark unless the reader has picked light before.
			head: [
				{ tag: "link", attrs: { rel: "preconnect", href: "https://fonts.googleapis.com" } },
				{ tag: "link", attrs: { rel: "stylesheet", href: "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" } },
				{
					tag: "script",
					content: "try{if(!localStorage.getItem('starlight-theme'))localStorage.setItem('starlight-theme','dark')}catch(e){}",
				},
			],
			sidebar: [
				{ label: "Start", items: [{ label: "Introduction", link: "/" }, "installation", "quickstart"] },
				{ label: "Lyrics", items: ["parsing", "building", "translations"] },
				{ label: "Display", items: ["scrolling-lyrics", "stage-lyrics", "playback", "singer-labels"] },
				{ label: "Look", items: ["theming", "better-lyrics-themes", "right-to-left"] },
				{ label: "More", items: ["custom-renderers", "upstream", { label: "API Reference", link: "/api/", attrs: { target: "_self" } }] },
			],
		}),
	],
});
