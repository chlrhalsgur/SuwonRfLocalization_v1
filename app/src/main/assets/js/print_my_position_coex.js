var sync_x_pos = 4730.01896-292;
var sync_y_pos = 619.3702-300;


function show_my_position(x_pos, y_pos) {
    var dot = $(`#dotContainer`)[0]
    dot.style.top = `${(sync_x_pos - x_pos)*0.65412}px`
    dot.style.left = `${(y_pos + sync_y_pos)*0.65389}px`
}

var currentAngle = 0; // 현재 회전 각도를 추적하기 위한 변수
function rotateArrow(targetAngle) {
    var arrow = document.getElementById('arrow');

    // 현재 각도와 목표 각도 사이의 차이를 계산합니다.
    var angleDifference = targetAngle - currentAngle;
    // -180도와 +180도 사이의 범위로 각도 차이를 정규화합니다.
    angleDifference = (angleDifference + 180) % 360 - 180;

    // 최소 회전을 위해 각도 차이가 180보다 크면 360에서 각도 차이를 뺍니다.
    if (angleDifference > 180) {
        angleDifference -= 360;
    }
    // 최소 회전을 위해 각도 차이가 -180보다 작으면 360을 더합니다.
    else if (angleDifference < -180) {
        angleDifference += 360;
    }

    // 현재 각도에 각도 차이를 더하여 새로운 회전 각도를 계산합니다.
    currentAngle += angleDifference;

    // 화살표 이미지를 새로운 각도로 회전시킵니다.
    arrow.style.transform = 'rotate(' + (90+currentAngle) + 'deg)';
}

var changeMapImage = function(fileName) {
    document.getElementById("map_img").src = `./images/${fileName}`;
};
