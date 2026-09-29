const rules = {
    eqeqeq: ["error", "always"],
    "no-constant-condition": "error",
    "no-dupe-args": "error",
    "no-undef": "error",
    "no-unreachable": "error",
    "no-unused-vars": "error"
};

export default [
    {
        files: ["src/main/resources/static/**/*.mjs"],
        languageOptions: {
            globals: {
                AbortController: "readonly",
                Element: "readonly",
                document: "readonly",
                window: "readonly"
            }
        },
        rules
    },
    {
        files: ["src/test/js/**/*.mjs"],
        languageOptions: {
            globals: {
                Response: "readonly",
                process: "readonly"
            }
        },
        rules
    }
];
