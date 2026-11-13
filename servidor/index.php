<?php
// Sin contenido publico: el colector solo ofrece /api/ (con token).
http_response_code(404);
header('Content-Type: text/plain; charset=utf-8');
echo "Not found\n";
